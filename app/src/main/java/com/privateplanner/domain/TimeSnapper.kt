package com.privateplanner.domain

import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.roundToInt

object TimeSnapper {
    const val MinutesPerDay = 24 * 60
    const val MinutesPerHour = 60
    const val SnapMinutes = 5
    const val DefaultDurationMinutes = 60
    const val MinimumDurationMinutes = 10

    fun floorToSnap(minutes: Int): Int {
        return minutes.coerceIn(0, MinutesPerDay - 1) / SnapMinutes * SnapMinutes
    }

    fun floorToValidStart(minutes: Int, durationMinutes: Int = MinimumDurationMinutes): Int {
        return clampStart(floorToSnap(minutes), durationMinutes)
    }

    fun snapDurationToNearest(minutes: Int): Int {
        return ((minutes + SnapMinutes / 2) / SnapMinutes * SnapMinutes)
            .coerceIn(MinimumDurationMinutes, MinutesPerDay)
    }

    fun minutesFromY(yPx: Float, hourHeightPx: Float): Int {
        val rawMinutes = floor(yPx / hourHeightPx * MinutesPerHour).toInt()
        return floorToSnap(rawMinutes)
    }

    fun deltaMinutesFromY(deltaPx: Float, hourHeightPx: Float): Int {
        return (deltaPx / hourHeightPx * MinutesPerHour / SnapMinutes)
            .roundToInt()
            .times(SnapMinutes)
    }

    fun defaultDurationForStart(startMinutes: Int, nextStartMinutes: Int?): Int {
        val remaining = MinutesPerDay - startMinutes
        val defaultDuration = DefaultDurationMinutes.coerceAtMost(remaining).coerceAtLeast(MinimumDurationMinutes)
        val gapToNext = nextStartMinutes
            ?.takeIf { it > startMinutes }
            ?.let { it - startMinutes }
            ?: return defaultDuration
        return gapToNext.coerceAtMost(defaultDuration).coerceAtLeast(MinimumDurationMinutes)
    }

    fun clampStart(startMinutes: Int, durationMinutes: Int): Int {
        return startMinutes.coerceIn(0, MinutesPerDay - durationMinutes)
    }

    fun clampDuration(startMinutes: Int, durationMinutes: Int): Int {
        return durationMinutes
            .coerceAtLeast(MinimumDurationMinutes)
            .coerceAtMost(MinutesPerDay - startMinutes)
    }

    fun minuteOfDay(time: LocalTime): Int = time.hour * MinutesPerHour + time.minute
}
