package com.privateplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSnapperTest {
    @Test
    fun floorToSnapUsesPreviousFiveMinuteMark() {
        assertEquals(9 * 60 + 5, TimeSnapper.floorToSnap(9 * 60 + 7))
        assertEquals(9 * 60 + 10, TimeSnapper.floorToSnap(9 * 60 + 14))
        assertEquals(9 * 60 + 20, TimeSnapper.floorToSnap(9 * 60 + 23))
        assertEquals(9 * 60 + 55, TimeSnapper.floorToSnap(9 * 60 + 59))
    }

    @Test
    fun defaultDurationCapsAtMidnight() {
        assertEquals(60, TimeSnapper.defaultDurationForStart(22 * 60, null))
        assertEquals(5, TimeSnapper.defaultDurationForStart(23 * 60 + 55, null))
    }

    @Test
    fun durationNeverRunsPastMidnight() {
        assertEquals(30, TimeSnapper.clampDuration(23 * 60 + 30, 60))
        assertEquals(5, TimeSnapper.clampDuration(10 * 60, 1))
    }

    @Test
    fun durationSnapCanReachFullDay() {
        assertEquals(24 * 60, TimeSnapper.snapDurationToNearest(24 * 60))
    }

    @Test
    fun defaultDurationUsesGapBeforeNextBlockWhenShorterThanAnHour() {
        assertEquals(35, TimeSnapper.defaultDurationForStart(9 * 60, 9 * 60 + 35))
        assertEquals(60, TimeSnapper.defaultDurationForStart(9 * 60, 10 * 60 + 15))
    }
}
