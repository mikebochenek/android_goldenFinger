package com.example.myapplication.jogging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JoggingTrackerTest {

    @Test
    fun joggingSession_uniqueIdGeneration() {
        val session1 = JoggingSession(
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            distanceMeters = 500f,
            finishReason = "Completed"
        )
        val session2 = JoggingSession(
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            distanceMeters = 500f,
            finishReason = "Completed"
        )

        assertNotEquals(session1.id, session2.id)
    }

    @Test
    fun joggingSession_filterById() {
        val session1 = JoggingSession(
            id = "test-session-1",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            distanceMeters = 500f,
            finishReason = "Completed"
        )
        val session2 = JoggingSession(
            id = "test-session-2",
            startTimeMs = 3000L,
            endTimeMs = 5000L,
            durationMs = 2000L,
            distanceMeters = 1200f,
            finishReason = "Completed"
        )

        val list = listOf(session1, session2)
        val filtered = list.filterNot { it.id == "test-session-1" }

        assertEquals(1, filtered.size)
        assertEquals("test-session-2", filtered[0].id)
    }

    @Test
    fun formatEuropeanDate_correctFormat() {
        // Timestamp for 2024-05-20 12:34:56 UTC/local
        val timestamp = 1716208496000L
        val formatted = formatEuropeanDate(timestamp)
        // Format should match dd/MM/yyyy HH:mm:ss
        val regex = Regex("""\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}""")
        org.junit.Assert.assertTrue("Date should match European format dd/MM/yyyy HH:mm:ss", regex.matches(formatted))
    }

    @Test
    fun exportSessionToLine_containsAllFieldsAndEuropeanDate() {
        val session = JoggingSession(
            id = "export-session-123",
            startTimeMs = 1716208496000L,
            endTimeMs = 1716212096000L,
            durationMs = 3600000L, // 1 hour
            distanceMeters = 10000f, // 10 km
            elevationGainMeters = 150f,
            finishReason = "Completed"
        )

        val exportedLine = exportSessionToLine(session)

        // Check single line (no unescaped newlines)
        assertEquals(1, exportedLine.lines().size)

        // Check European date format presence
        org.junit.Assert.assertTrue(exportedLine.contains("Date: "))
        org.junit.Assert.assertTrue(exportedLine.contains("Run Time: 01:00:00"))
        org.junit.Assert.assertTrue(exportedLine.contains("Distance: 10.00 km"))
        org.junit.Assert.assertTrue(exportedLine.contains("Elevation Gain: +150 m"))
        org.junit.Assert.assertTrue(exportedLine.contains("Pace: "))
        org.junit.Assert.assertTrue(exportedLine.contains("Speed: 10.0 km/h"))
        org.junit.Assert.assertTrue(exportedLine.contains("Reason: Completed"))
        org.junit.Assert.assertTrue(exportedLine.contains("ID: export-session-123"))
        org.junit.Assert.assertTrue(exportedLine.contains("StartMs: 1716208496000"))
        org.junit.Assert.assertTrue(exportedLine.contains("EndMs: 1716212096000"))
        org.junit.Assert.assertTrue(exportedLine.contains("DurationMs: 3600000"))
        org.junit.Assert.assertTrue(exportedLine.contains("DistanceMeters: 10000.0"))
        org.junit.Assert.assertTrue(exportedLine.contains("ElevationMeters: 150.0"))
    }

    @Test
    fun exportSessions_oneEntryPerLine() {
        val session1 = JoggingSession(
            id = "session-1",
            startTimeMs = 1000000000000L,
            endTimeMs = 1000001000000L,
            durationMs = 1000000L,
            distanceMeters = 2000f,
            elevationGainMeters = 20f,
            finishReason = "User Stopped"
        )
        val session2 = JoggingSession(
            id = "session-2",
            startTimeMs = 1000002000000L,
            endTimeMs = 1000005000000L,
            durationMs = 3000000L,
            distanceMeters = 5000f,
            elevationGainMeters = 50f,
            finishReason = "Completed"
        )

        val exportedText = exportSessions(listOf(session1, session2))
        val lines = exportedText.lines()

        assertEquals(2, lines.size)
        org.junit.Assert.assertTrue(lines[0].contains("ID: session-1"))
        org.junit.Assert.assertTrue(lines[1].contains("ID: session-2"))
    }

    @Test
    fun exportSessions_emptyList() {
        val exportedText = exportSessions(emptyList())
        assertEquals("No jogging sessions to export.", exportedText)
    }
}
