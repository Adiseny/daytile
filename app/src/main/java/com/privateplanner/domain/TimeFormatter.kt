package com.privateplanner.domain

object TimeFormatter {
    fun time(minutes: Int): String = buildString(capacity = 5) { appendTime(minutes) }

    private fun StringBuilder.appendTime(minutes: Int) {
        val clamped = minutes.coerceIn(0, TimeSnapper.MinutesPerDay)
        val hour = clamped / TimeSnapper.MinutesPerHour
        val minute = clamped % TimeSnapper.MinutesPerHour
        append(hour)
        append(':')
        if (minute < 10) append('0')
        append(minute)
    }

    // An en dash on screen; read aloud, " to ".
    fun range(startMinutes: Int, durationMinutes: Int, separator: String = " \u2013 "): String {
        return buildString(capacity = 10 + separator.length) {
            appendTime(startMinutes)
            append(separator)
            appendTime(startMinutes + durationMinutes)
        }
    }

    fun duration(durationMinutes: Int): String {
        val hours = durationMinutes / TimeSnapper.MinutesPerHour
        val minutes = durationMinutes % TimeSnapper.MinutesPerHour
        return when {
            hours == 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }
}
