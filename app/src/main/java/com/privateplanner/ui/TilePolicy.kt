package com.privateplanner.ui

internal const val HourHeightDp = 120f
internal const val DurationVisibleMinWidthDp = 112f
internal const val DurationTitleRemainderMinDp = 56f
internal const val DurationMaxReserveFraction = 0.34f
internal const val LongTitlePinMinHeightDp = 240f

internal data class DurationDisplayDecision(
    val show: Boolean,
    val reserveDp: Float
)

internal object TilePolicy {
    fun durationDisplayDecision(
        tileWidthDp: Float,
        durationText: String,
        compact: Boolean,
        durationFontSizeSp: Float = if (compact) 11f else 12f
    ): DurationDisplayDecision {
        if (tileWidthDp < DurationVisibleMinWidthDp) {
            return DurationDisplayDecision(show = false, reserveDp = 0f)
        }
        val estimatedTextWidthDp = durationText.length * durationFontSizeSp * 0.58f
        val desiredReserveDp = estimatedTextWidthDp + if (compact) 10f else 12f
        val maxReserveDp = tileWidthDp * DurationMaxReserveFraction
        if (desiredReserveDp > maxReserveDp) {
            return DurationDisplayDecision(show = false, reserveDp = 0f)
        }
        val reserveDp = desiredReserveDp.coerceAtMost(maxReserveDp)
        return DurationDisplayDecision(
            show = tileWidthDp - reserveDp >= DurationTitleRemainderMinDp,
            reserveDp = reserveDp
        )
    }
}
