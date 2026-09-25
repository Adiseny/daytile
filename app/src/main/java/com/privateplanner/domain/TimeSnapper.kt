package com.privateplanner.domain

import java.time.LocalDate
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.roundToInt

object TimeSnapper {
    const val MinutesPerDay = 24 * 60
    const val MinutesPerHour = 60
    const val SnapMinutes = 5
    const val DefaultDurationMinutes = 60
    const val MinimumDurationMinutes = 10
    const val MillisPerMinute = 60_000L

    fun floorToSnap(minutes: Int): Int {
        return minutes.coerceIn(0, MinutesPerDay - 1) / SnapMinutes * SnapMinutes
    }

    fun floorToValidStart(minutes: Int): Int {
        return clampStart(floorToSnap(minutes), MinimumDurationMinutes)
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
        return capDurationAtNextStart(startMinutes, defaultDuration, nextStartMinutes)
    }

    fun capDurationAtNextStart(
        startMinutes: Int,
        durationMinutes: Int,
        nextStartMinutes: Int?
    ): Int {
        val gapToNext = nextStartMinutes
            ?.takeIf { it > startMinutes }
            ?.let { it - startMinutes }
            ?: return durationMinutes
        return durationMinutes.coerceAtMost(gapToNext.coerceAtLeast(MinimumDurationMinutes))
    }

    fun clampStart(startMinutes: Int, durationMinutes: Int): Int {
        return startMinutes.coerceIn(0, MinutesPerDay - durationMinutes)
    }

    fun clampDuration(startMinutes: Int, durationMinutes: Int): Int {
        return durationMinutes
            .coerceAtLeast(MinimumDurationMinutes)
            .coerceAtMost(MinutesPerDay - startMinutes)
    }

    // The wall clock as milliseconds since the local epoch, from the platform's time zone,
    // which every process already has loaded. java.time builds its zone rules on first
    // use, which would put milliseconds of work on the main thread at launch.
    fun localNowMillis(): Long {
        val now = System.currentTimeMillis()
        return now + TimeZone.getDefault().getOffset(now)
    }

    fun minuteOfDay(localMillis: Long): Int =
        Math.floorMod(Math.floorDiv(localMillis, MillisPerMinute), MinutesPerDay.toLong()).toInt()

    fun dateOf(localMillis: Long): LocalDate =
        LocalDate.ofEpochDay(Math.floorDiv(localMillis, MillisPerMinute * MinutesPerDay))
}
