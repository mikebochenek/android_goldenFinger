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
}
