package com.privateplanner

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReminderTimeTest {
    private lateinit var previous: TimeZone

    @Before
    fun useLondon() {
        previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(London))
    }

    @After
    fun restore() {
        TimeZone.setDefault(previous)
    }

    @Test
    fun wallClockMinutesKeepTheirTimeAcrossDaylightSavingChanges() {
        // Clocks go forward at 01:00 on 29 March 2026 and back at 02:00 on 25 October.
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            for (minutes in listOf(0, 9 * 60, 13 * 60 + 5, 23 * 60 + 55)) {
                assertEquals(
                    "$date +$minutes",
                    date.atStartOfDay().plusMinutes(minutes.toLong()).atZone(ZoneId.of(London)).toInstant().toEpochMilli(),
                    millisAt(date, minutes)
                )
            }
        }
        // 09:00 BST is 08:00 UTC, not the 09:00 UTC that elapsed minutes from midnight give.
        assertEquals(
            LocalDateTime.of(2026, 3, 29, 8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            millisAt(LocalDate.of(2026, 3, 29), 9 * 60)
        )
    }

    @Test
    fun endOfDayIsTheNextMidnight() {
        val date = LocalDate.of(2026, 10, 25)
        assertEquals(millisAt(date.plusDays(1), 0), millisAt(date, 24 * 60))
    }

    private companion object {
        const val London = "Europe/London"
    }
}
