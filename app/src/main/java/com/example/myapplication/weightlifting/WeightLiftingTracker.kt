package com.example.myapplication.weightlifting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Data class representing daily lifting and exercise tracking counts.
 */
data class ExerciseDayEntry(
    val date: String, // Date in "yyyy-MM-dd" format
    val pushups: Int = 0,
    val situps: Int = 0,
    val squats: Int = 0,
    val curls: Int = 0
) {
    val totalSets: Int
        get() = pushups + situps + squats + curls
}

/**
 * Storage helper for persisting daily exercise entries in SharedPreferences.
 */
object WeightLiftingStorage {
    private const val PREFS_NAME = "weight_lifting_prefs"
    private const val KEY_ENTRIES = "daily_exercise_entries"

    fun getEntriesMap(context: Context): Map<String, ExerciseDayEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        val map = mutableMapOf<String, ExerciseDayEntry>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val date = obj.getString("date")
                map[date] = ExerciseDayEntry(
                    date = date,
                    pushups = obj.optInt("pushups", 0),
                    situps = obj.optInt("situps", 0),
                    squats = obj.optInt("squats", 0),
                    curls = obj.optInt("curls", 0)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun getAllEntries(context: Context): List<ExerciseDayEntry> {
        return getEntriesMap(context).values.sortedByDescending { it.date }
    }

    fun getEntryForDate(context: Context, date: String): ExerciseDayEntry {
        return getEntriesMap(context)[date] ?: ExerciseDayEntry(date = date)
    }

    fun saveEntry(context: Context, entry: ExerciseDayEntry) {
        val map = getEntriesMap(context).toMutableMap()
        if (entry.totalSets == 0) {
            map.remove(entry.date)
        } else {
            map[entry.date] = entry
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        map.values.sortedByDescending { it.date }.forEach { item ->
            val obj = JSONObject().apply {
                put("date", item.date)
                put("pushups", item.pushups)
                put("situps", item.situps)
                put("squats", item.squats)
                put("curls", item.curls)
            }
            jsonArray.put(obj)
        }
        prefs.edit { putString(KEY_ENTRIES, jsonArray.toString()) }
    }

    fun generateFormattedHistoryText(context: Context): String {
        val entries = getAllEntries(context)
        if (entries.isEmpty()) {
            return "No exercise history recorded yet."
        }
        val sb = StringBuilder()
        sb.appendLine("=== Lifting & Exercise History ===")
        sb.appendLine()
        var grandTotal = 0
        var totalPushups = 0
        var totalSitups = 0
        var totalSquats = 0
        var totalCurls = 0

        entries.forEach { entry ->
            sb.appendLine("📅 Date: ${entry.date}")
            sb.appendLine("  • Pushups: ${entry.pushups}")
            sb.appendLine("  • Situps:  ${entry.situps}")
            sb.appendLine("  • Squats:  ${entry.squats}")
            sb.appendLine("  • Curls:   ${entry.curls}")
            sb.appendLine("  Total for Day: ${entry.totalSets} sets")
            sb.appendLine()

            grandTotal += entry.totalSets
            totalPushups += entry.pushups
            totalSitups += entry.situps
            totalSquats += entry.squats
            totalCurls += entry.curls
        }

        sb.appendLine("========================================")
        sb.appendLine("OVERALL SUMMARY (${entries.size} days tracked):")
        sb.appendLine("Pushups: $totalPushups | Situps: $totalSitups | Squats: $totalSquats | Curls: $totalCurls")
        sb.appendLine("Grand Total Sets: $grandTotal")
        return sb.toString().trimEnd()
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun getTodayDateString(): String {
    return dateFormat.format(Date())
}

private fun getRelativeDateString(currentDateStr: String, dayOffset: Int): String {
    return try {
        val date = dateFormat.parse(currentDateStr) ?: Date()
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        dateFormat.format(cal.time)
    } catch (e: Exception) {
        currentDateStr
    }
}

/**
 * Screen composable for tracking daily lifting and bodyweight exercises.
 */
@Composable
fun WeightLiftingTrackerScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedDate by remember { mutableStateOf(getTodayDateString()) }

    var pushups by remember { mutableIntStateOf(0) }
    var situps by remember { mutableIntStateOf(0) }
    var squats by remember { mutableIntStateOf(0) }
    var curls by remember { mutableIntStateOf(0) }

    var historyText by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf("") }

    // Load entry whenever date changes
    fun loadForDate(date: String) {
        val entry = WeightLiftingStorage.getEntryForDate(context, date)
        pushups = entry.pushups
        situps = entry.situps
        squats = entry.squats
        curls = entry.curls
        historyText = WeightLiftingStorage.generateFormattedHistoryText(context)
        saveMessage = ""
    }

    LaunchedEffect(selectedDate) {
        loadForDate(selectedDate)
    }

    fun saveCurrentEntry() {
        val entry = ExerciseDayEntry(
            date = selectedDate,
            pushups = pushups,
            situps = situps,
            squats = squats,
            curls = curls
        )
        WeightLiftingStorage.saveEntry(context, entry)
        historyText = WeightLiftingStorage.generateFormattedHistoryText(context)
        saveMessage = "Saved for $selectedDate!"
    }

    val dailyTotal = pushups + situps + squats + curls

    Column(
        modifier = modifier
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title & Header
        Text(
            text = "🏋️ Exercise & Lifting Tracker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Date Selector Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select Given Day",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { selectedDate = getRelativeDateString(selectedDate, -1) }
                    ) {
                        Text("◀ Prev")
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = selectedDate,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedDate == getTodayDateString()) {
                            Text(
                                text = "(Today)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { selectedDate = getRelativeDateString(selectedDate, 1) }
                    ) {
                        Text("Next ▶")
                    }
                }

                if (selectedDate != getTodayDateString()) {
                    Button(
                        onClick = { selectedDate = getTodayDateString() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Jump to Today", fontSize = 12.sp)
                    }
                }
            }
        }

        // Daily Exercise Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Workout for $selectedDate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                // Pushups
                ExerciseInputRow(
                    label = "💪 Pushups",
                    value = pushups,
                    onValueChange = {
                        pushups = it
                        saveCurrentEntry()
                    }
                )

                // Situps
                ExerciseInputRow(
                    label = "🧘 Situps",
                    value = situps,
                    onValueChange = {
                        situps = it
                        saveCurrentEntry()
                    }
                )

                // Squats
                ExerciseInputRow(
                    label = "🦵 Squats",
                    value = squats,
                    onValueChange = {
                        squats = it
                        saveCurrentEntry()
                    }
                )

                // Curls
                ExerciseInputRow(
                    label = "🏋️ Curls",
                    value = curls,
                    onValueChange = {
                        curls = it
                        saveCurrentEntry()
                    }
                )

                HorizontalDivider()

                // Daily Total Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Daily Total:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "$dailyTotal sets",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (saveMessage.isNotEmpty()) {
                    Text(
                        text = saveMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = { saveCurrentEntry() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💾 Save / Update Entry")
                }
            }
        }

        // Text History & Cut/Paste Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "📜 Text History & Copy / Paste",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "You can select text below to copy/cut, or tap 'Copy History' to copy the entire log to your clipboard for use in other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Exercise History", historyText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied history to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("📋 Copy History to Clipboard")
                }

                // Copyable text container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = historyText,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Helper row composable for entering and quickly incrementing/decrementing exercise counts.
 */
@Composable
private fun ExerciseInputRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "$value sets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { onValueChange((value - 5).coerceAtLeast(0)) },
                modifier = Modifier.weight(1f)
            ) {
                Text("-5", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(0)) },
                modifier = Modifier.weight(1f)
            ) {
                Text("-1", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.weight(1f)
            ) {
                Text("+1", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onValueChange(value + 5) },
                modifier = Modifier.weight(1f)
            ) {
                Text("+5", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onValueChange(value + 10) },
                modifier = Modifier.weight(1f)
            ) {
                Text("+10", fontSize = 12.sp)
            }
        }
    }
}
