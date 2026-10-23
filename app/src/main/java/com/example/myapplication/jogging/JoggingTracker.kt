package com.example.myapplication.jogging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
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
 * Handles location updates from FusedLocationProviderClient or standard LocationManager.
 */
class LocationTracker(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var systemLocationListener: LocationListener? = null
    private var lastLocation: Location? = null

    @SuppressLint("MissingPermission")
    fun startTracking(onDistanceUpdated: (deltaMeters: Float) -> Unit) {
        lastLocation = null
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000L
            ).setMinUpdateDistanceMeters(1f).build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        processNewLocation(loc, onDistanceUpdated)
                    }
                }
            }
            locationCallback = cb
            fusedLocationClient.requestLocationUpdates(locationRequest, cb, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
            fallbackLocationManager(onDistanceUpdated)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackLocationManager(onDistanceUpdated: (deltaMeters: Float) -> Unit) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm
            val listener = LocationListener { location ->
                processNewLocation(location, onDistanceUpdated)
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

    private fun processNewLocation(location: Location, onDistanceUpdated: (deltaMeters: Float) -> Unit) {
        if (location.hasAccuracy() && location.accuracy > 50f) return

        val prev = lastLocation
        if (prev != null) {
            val distance = prev.distanceTo(location)
            if (distance in 0.5f..300f) {
                onDistanceUpdated(distance)
            }
        }
        lastLocation = location
    }

    fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        systemLocationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        systemLocationListener = null
        lastLocation = null
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
 * Provides biometric fingerprint unlock to start jogging, real-time distance and duration tracking,
 * automatic termination after 2 hours, and run history.
 */
@Composable
fun JoggingTrackerScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    
    val locationTracker = remember { LocationTracker(context) }
    
    var isTracking by remember { mutableStateOf(false) }
    var startTimeMs by remember { mutableLongStateOf(0L) }
    var elapsedTimeMs by remember { mutableLongStateOf(0L) }
    var distanceMeters by remember { mutableFloatStateOf(0f) }
    
    var statusMessage by remember { mutableStateOf("Touch Fingerprint Sensor to Start Run") }
    var statusColor by remember { mutableStateOf(Color(0xFFFC4C02)) } // Strava Orange
    
    var sessionList by remember { mutableStateOf(JoggingStorage.getSessions(context)) }

    fun refreshHistory() {
        sessionList = JoggingStorage.getSessions(context)
    }

    fun stopSession(reason: String) {
        if (!isTracking) return
        val endTime = System.currentTimeMillis()
        val finalDuration = elapsedTimeMs
        val finalDistance = distanceMeters
        
        isTracking = false
        locationTracker.stopTracking()
        
        val newSession = JoggingSession(
            startTimeMs = startTimeMs,
            endTimeMs = endTime,
            durationMs = finalDuration,
            distanceMeters = finalDistance,
            finishReason = reason
        )
        JoggingStorage.saveSession(context, newSession)
        refreshHistory()
        
        statusMessage = "Run finished! ($reason)"
        statusColor = if (reason.contains("2-Hour")) Color(0xFFD32F2F) else Color(0xFF388E3C)
    }

    fun startSession() {
        distanceMeters = 0f
        elapsedTimeMs = 0L
        startTimeMs = System.currentTimeMillis()
        isTracking = true
        statusMessage = "Jogging in progress..."
        statusColor = Color(0xFFFC4C02)
        
        locationTracker.startTracking { delta ->
            distanceMeters += delta
        }
    }

    // Permission launcher for location access
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startSession()
        } else {
            Toast.makeText(context, "Location permission required to track distance", Toast.LENGTH_LONG).show()
            statusMessage = "Location permission denied"
            statusColor = Color.Red
        }
    }

    fun checkPermissionsAndStart() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            startSession()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun triggerFingerprintUnlock() {
        if (activity == null) {
            checkPermissionsAndStart()
            return
        }

        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // If biometrics not set up or hardware unavailable, fallback to direct start
            Toast.makeText(context, "Biometric unavailable. Starting session...", Toast.LENGTH_SHORT).show()
            checkPermissionsAndStart()
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
                    checkPermissionsAndStart()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    statusMessage = "Fingerprint not recognized"
                    statusColor = Color.Red
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Jogging Tracker")
            .setSubtitle("Touch sensor to start tracking distance & time")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // Timer loop with auto-stop at 2 hours limit
    LaunchedEffect(isTracking) {
        if (isTracking) {
            val startMs = System.currentTimeMillis()
            while (isTracking) {
                val currentMs = System.currentTimeMillis()
                val elapsed = currentMs - startMs
                if (elapsed >= MAX_JOGGING_DURATION_MS) {
                    elapsedTimeMs = MAX_JOGGING_DURATION_MS
                    stopSession("Auto-stopped (2-Hour Max Limit)")
                    break
                } else {
                    elapsedTimeMs = elapsed
                }
                delay(100L)
            }
        }
    }

    // Clean up on dispose
    DisposableEffect(Unit) {
        onDispose {
            locationTracker.stopTracking()
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
                    text = "RUN TRACKER",
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
                    text = "Auto-stops after maximum 2 hours",
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
                onClick = { triggerFingerprintUnlock() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFC4C02)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Unlock with Fingerprint & Start Jogging",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        } else {
            Button(
                onClick = { stopSession("Stopped by User") },
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
