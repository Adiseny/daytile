package com.privateplanner.ui

private const val DurationVisibleMinWidthDp = 112f
private const val DurationTitleRemainderMinDp = 56f
private const val DurationMaxReserveFraction = 0.34f

internal fun durationReserveDp(
    tileWidthDp: Float,
    durationText: String,
    compact: Boolean,
    durationFontSizeSp: Float,
    fontScale: Float
): Float {
    if (tileWidthDp < DurationVisibleMinWidthDp) return 0f
    val estimatedTextWidthDp = durationText.length * durationFontSizeSp * fontScale * 0.58f
    val reserveDp = estimatedTextWidthDp + if (compact) 10f else 12f
    val maxReserveDp = tileWidthDp * DurationMaxReserveFraction
    return if (
        reserveDp <= maxReserveDp &&
        tileWidthDp - reserveDp >= DurationTitleRemainderMinDp
    ) {
        reserveDp
    } else {
        0f
    }
}
