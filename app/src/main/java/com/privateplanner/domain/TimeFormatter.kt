package com.privateplanner.domain

object TimeFormatter {
    fun time(minutes: Int): String {
        val clamped = minutes.coerceIn(0, TimeSnapper.MinutesPerDay)
        val hour = clamped / TimeSnapper.MinutesPerHour
        val minute = clamped % TimeSnapper.MinutesPerHour
        return buildString(capacity = 5) {
            append(hour)
            append(':')
            if (minute < 10) append('0')
            append(minute)
        }
    }

    fun range(startMinutes: Int, durationMinutes: Int): String {
        return "${time(startMinutes)} \u2013 ${time(startMinutes + durationMinutes)}"
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

    fun spokenRange(startMinutes: Int, durationMinutes: Int): String {
        return "${time(startMinutes)} to ${time(startMinutes + durationMinutes)}"
    }
}
