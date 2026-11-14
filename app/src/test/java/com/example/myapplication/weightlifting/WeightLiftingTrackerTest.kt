package com.example.myapplication.weightlifting

import org.junit.Assert.assertEquals
import org.junit.Test

class WeightLiftingTrackerTest {

    @Test
    fun exerciseDayEntry_totalReps_calculationIsCorrect() {
        val entry = ExerciseDayEntry(
            date = "2026-03-30",
            pushups = 25,
            situps = 30,
            squats = 40,
            curls = 15
        )

        assertEquals(110, entry.totalReps)
    }

    @Test
    fun exerciseDayEntry_defaultValuesZero() {
        val entry = ExerciseDayEntry(date = "2026-03-30")

        assertEquals(0, entry.pushups)
        assertEquals(0, entry.situps)
        assertEquals(0, entry.squats)
        assertEquals(0, entry.curls)
        assertEquals(0, entry.totalReps)
    }
}
