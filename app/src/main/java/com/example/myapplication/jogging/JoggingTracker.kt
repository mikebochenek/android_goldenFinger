package com.example.myapplication.jogging

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Maximum duration for a jogging session: 2 hours (in milliseconds).
 */
const val MAX_JOGGING_DURATION_MS = 2 * 60 * 60 * 1000L // 7,200,000 ms = 2 hours

/**
 * Data class representing a completed jogging session.
 */
data class JoggingSession(
    val id: String = UUID.randomUUID().toString(),
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val distanceMeters: Float,
    val finishReason: String
)

/**
 * Persistence helper for storing and retrieving jogging sessions in SharedPreferences.
 */
object JoggingStorage {
    private const val PREFS_NAME = "jogging_prefs"
    private const val KEY_SESSIONS = "saved_sessions"

    fun saveSession(context: Context, session: JoggingSession) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sessions = getSessions(context).toMutableList()
        sessions.add(0, session)
        val jsonArray = JSONArray()
        sessions.take(30).forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("startTimeMs", item.startTimeMs)
                put("endTimeMs", item.endTimeMs)
                put("durationMs", item.durationMs)
                put("distanceMeters", item.distanceMeters.toDouble())
                put("finishReason", item.finishReason)
            }
            jsonArray.put(obj)
        }
        prefs.edit { putString(KEY_SESSIONS, jsonArray.toString()) }
    }

    fun getSessions(context: Context): List<JoggingSession> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val list = mutableListOf<JoggingSession>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    JoggingSession(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        startTimeMs = obj.getLong("startTimeMs"),
                        endTimeMs = obj.getLong("endTimeMs"),
                        durationMs = obj.getLong("durationMs"),
                        distanceMeters = obj.getDouble("distanceMeters").toFloat(),
                        finishReason = obj.optString("finishReason", "Completed")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearSessions(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_SESSIONS) }
    }
}

/**
 * Shared state manager for the active jogging session across UI and Foreground Service.
 */
object JoggingTrackerManager {
    data class JoggingState(
        val isTracking: Boolean = false,
        val startTimeMs: Long = 0L,
        val elapsedTimeMs: Long = 0L,
        val distanceMeters: Float = 0f,
        val finishReason: String? = null
    )

    private val _state = MutableStateFlow(JoggingState())
    val state: StateFlow<JoggingState> = _state.asStateFlow()

    fun updateState(updater: (JoggingState) -> JoggingState) {
        _state.value = updater(_state.value)
    }
}

/**
 * Foreground Service that keeps distance tracking and elapsed time running in the background,
 * even when the app is minimized or screen is locked.
 */
class JoggingService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var systemLocationListener: LocationListener? = null
    private var startTimeMs: Long = 0L
    private var lastLocation: Location? = null
    private var timerJob: kotlinx.coroutines.Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "jogging_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRun("Stopped by User")
            }
            else -> {
                if (!JoggingTrackerManager.state.value.isTracking) {
                    startRun()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRun() {
        startTimeMs = System.currentTimeMillis()
        JoggingTrackerManager.updateState {
            it.copy(
                isTracking = true,
                startTimeMs = startTimeMs,
                elapsedTimeMs = 0L,
                distanceMeters = 0f,
                finishReason = null
            )
        }

        val notification = buildNotification(0f, 0L)
        startForeground(NOTIFICATION_ID, notification)

        // Request location updates via FusedLocationProviderClient
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000L
            ).setMinUpdateDistanceMeters(1f).build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        processNewLocation(loc)
                    }
                }
            }
            locationCallback = cb
            fusedLocationClient.requestLocationUpdates(locationRequest, cb, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
            fallbackLocationManager()
        }

        // Start timer job
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(100L)
                val current = System.currentTimeMillis()
                val elapsed = current - startTimeMs
                if (elapsed >= MAX_JOGGING_DURATION_MS) {
                    JoggingTrackerManager.updateState {
                        it.copy(
                            elapsedTimeMs = MAX_JOGGING_DURATION_MS
                        )
                    }
                    stopRun("Auto-stopped (2-Hour Max Limit)")
                    break
                } else {
                    JoggingTrackerManager.updateState {
                        it.copy(
                            elapsedTimeMs = elapsed
                        )
                    }
                    updateNotification(JoggingTrackerManager.state.value.distanceMeters, elapsed)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackLocationManager() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm
            val listener = LocationListener { location ->
                processNewLocation(location)
            }
            systemLocationListener = listener
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, listener)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, listener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processNewLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 50f) return

        val prev = lastLocation
        if (prev != null) {
            val distance = prev.distanceTo(location)
            if (distance in 0.5f..300f) {
                JoggingTrackerManager.updateState { state ->
                    val newDist = state.distanceMeters + distance
                    updateNotification(newDist, state.elapsedTimeMs)
                    state.copy(distanceMeters = newDist)
                }
            }
        }
        lastLocation = location
    }

    private fun stopRun(reason: String) {
        timerJob?.cancel()
        timerJob = null

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null

        systemLocationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        systemLocationListener = null
        lastLocation = null

        val state = JoggingTrackerManager.state.value
        if (state.isTracking || state.startTimeMs > 0L) {
            val endTime = System.currentTimeMillis()
            val session = JoggingSession(
                startTimeMs = state.startTimeMs,
                endTimeMs = endTime,
                durationMs = state.elapsedTimeMs,
                distanceMeters = state.distanceMeters,
                finishReason = reason
            )
            JoggingStorage.saveSession(this, session)
        }

        JoggingTrackerManager.updateState {
            it.copy(
                isTracking = false,
                finishReason = reason
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jogging Tracker Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks distance and duration in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(distanceMeters: Float, elapsedTimeMs: Long): android.app.Notification {
        val stopIntent = Intent(this, JoggingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "Dist: ${formatDistance(distanceMeters)} | Time: ${formatDuration(elapsedTimeMs)}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🏃 Jogging Tracker Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Run", stopPendingIntent)
            .build()
    }

    private fun updateNotification(distanceMeters: Float, elapsedTimeMs: Long) {
        val notification = buildNotification(distanceMeters, elapsedTimeMs)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

// Formatting helpers
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatDistance(meters: Float): String {
    return if (meters >= 1000f) {
        String.format(Locale.getDefault(), "%.2f km", meters / 1000f)
    } else {
        String.format(Locale.getDefault(), "%d m", meters.roundToInt())
    }
}

fun formatPace(distanceMeters: Float, durationMs: Long): String {
    if (distanceMeters <= 20f || durationMs < 5000L) return "--:-- /km"
    val km = distanceMeters / 1000f
    val totalSeconds = durationMs / 1000f
    val secondsPerKm = (totalSeconds / km).toLong()
    val minutes = secondsPerKm / 60
    val seconds = secondsPerKm % 60
    if (minutes > 99) return "--:-- /km"
    return String.format(Locale.getDefault(), "%d'%02d\" /km", minutes, seconds)
}

fun formatSpeed(distanceMeters: Float, durationMs: Long): String {
    if (durationMs < 1000L) return "0.0 km/h"
    val hours = durationMs / 3600000f
    val km = distanceMeters / 1000f
    val speedKmH = if (hours > 0f) km / hours else 0f
    return String.format(Locale.getDefault(), "%.1f km/h", speedKmH)
}

fun formatDate(ms: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}

/**
 * Main Strava-like Jogging Tracker Composable UI.
 *
 * Provides biometric fingerprint unlock to start jogging, real-time distance and duration tracking
 * running in a Foreground Service (surviving app minimize and screen lock),
 * automatic termination after 2 hours, and run history.
 */
@Composable
fun JoggingTrackerScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    val trackerState by JoggingTrackerManager.state.collectAsState()
    val isTracking = trackerState.isTracking
    val elapsedTimeMs = trackerState.elapsedTimeMs
    val distanceMeters = trackerState.distanceMeters
    val finishReason = trackerState.finishReason
    
    var statusMessage by remember(isTracking, finishReason) {
        mutableStateOf(
            when {
                isTracking -> "Jogging in progress (Background Service Active)..."
                finishReason != null -> "Run finished! ($finishReason)"
                else -> "Touch Fingerprint Sensor to Start Run"
            }
        )
    }
    var statusColor by remember(isTracking, finishReason) {
        mutableStateOf(
            when {
                isTracking -> Color(0xFFFC4C02)
                finishReason?.contains("2-Hour") == true -> Color(0xFFD32F2F)
                finishReason != null -> Color(0xFF388E3C)
                else -> Color(0xFFFC4C02)
            }
        )
    }
    
    var sessionList by remember { mutableStateOf(JoggingStorage.getSessions(context)) }

    fun refreshHistory() {
        sessionList = JoggingStorage.getSessions(context)
    }

    // Refresh history periodically or when tracking changes
    androidx.compose.runtime.LaunchedEffect(isTracking) {
        refreshHistory()
    }

    fun startServiceSession() {
        val intent = Intent(context, JoggingService::class.java).apply {
            action = JoggingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopServiceSession() {
        val intent = Intent(context, JoggingService::class.java).apply {
            action = JoggingService.ACTION_STOP
        }
        context.startService(intent)
        refreshHistory()
    }

    fun triggerFingerprintUnlock() {
        if (activity == null) {
            startServiceSession()
            return
        }

        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(context, "Biometric unavailable. Starting background session...", Toast.LENGTH_SHORT).show()
            startServiceSession()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    statusMessage = "Unlock error: $errString"
                    statusColor = Color.Red
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    startServiceSession()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    statusMessage = "Fingerprint not recognized"
                    statusColor = Color.Red
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Jogging Tracker")
            .setSubtitle("Touch sensor to start background tracking")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // Permission launcher for location access and notifications (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val grantedLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (grantedLocation) {
            triggerFingerprintUnlock()
        } else {
            Toast.makeText(context, "Location permission required to track distance", Toast.LENGTH_LONG).show()
            statusMessage = "Location permission denied"
            statusColor = Color.Red
        }
    }

    fun startRunFlow() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasFine || hasCoarse) {
            if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            } else {
                triggerFingerprintUnlock()
            }
        } else {
            val permissionsList = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissionsList.toTypedArray())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Strava Style Header Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFC4C02) // Strava Brand Orange
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "RUN TRACKER (BACKGROUND ENABLED)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Fingerprint Unlocked Jogging",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Runs in background (even with screen locked) up to 2 hours",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }

        // Live Metric Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Main Distance Display
                Text(
                    text = "DISTANCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDistance(distanceMeters),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isTracking) Color(0xFFFC4C02) else MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Secondary Metrics Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MetricBox(
                        label = "DURATION",
                        value = formatDuration(elapsedTimeMs)
                    )
                    MetricBox(
                        label = "PACE",
                        value = formatPace(distanceMeters, elapsedTimeMs)
                    )
                    MetricBox(
                        label = "SPEED",
                        value = formatSpeed(distanceMeters, elapsedTimeMs)
                    )
                }

                // 2-Hour Limit Progress Bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val progress = (elapsedTimeMs.toFloat() / MAX_JOGGING_DURATION_MS).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "2-Hour Time Limit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(progress * 100).roundToInt()}% (${formatDuration(MAX_JOGGING_DURATION_MS - elapsedTimeMs)} left)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (progress > 0.9f) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (progress > 0.9f) Color.Red else Color(0xFFFC4C02),
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }

        // Status Message
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = statusColor.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, statusColor)
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }

        // Action Buttons
        if (!isTracking) {
            Button(
                onClick = { startRunFlow() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFC4C02)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Unlock with Fingerprint & Start Run",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        } else {
            Button(
                onClick = { stopServiceSession() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F) // Danger Red
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Finish Run & Save",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Run History Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Jogging Sessions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (sessionList.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        JoggingStorage.clearSessions(context)
                        refreshHistory()
                    }
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (sessionList.isEmpty()) {
            Text(
                text = "No completed jogging sessions yet. Unlock with fingerprint to record your first run!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sessionList.take(5).forEach { session ->
                    SessionItemCard(session)
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SessionItemCard(session: JoggingSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDate(session.startTimeMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = session.finishReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.finishReason.contains("2-Hour")) Color.Red else Color(0xFF388E3C)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDistance(session.distanceMeters),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFC4C02)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Time: ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Pace: ${formatPace(session.distanceMeters, session.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
