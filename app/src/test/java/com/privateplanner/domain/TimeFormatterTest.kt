package com.privateplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {
    @Test
    fun rangesPreserveDisplayAndSpokenFormatting() {
        assertEquals("0:00", TimeFormatter.time(-1))
        assertEquals("0:05", TimeFormatter.time(5))
        assertEquals("24:00", TimeFormatter.time(1441))
        assertEquals("9:05 \u2013 10:00", TimeFormatter.range(545, 55))
        assertEquals("23:50 to 24:00", TimeFormatter.range(1430, 10, " to "))
        assertEquals("0:00 \u2013 24:00", TimeFormatter.range(0, 1440))
    }
}
