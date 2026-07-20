package com.privateplanner.domain

private const val ColoursPerPeriod = 3
private val BlockColours = longArrayOf(
    0xFF6F7772, 0xFF64717A, 0xFF7B7068,
    0xFFC38A24, 0xFFB97820, 0xFFD49D35,
    0xFF6F9B72, 0xFF5F8D68, 0xFF84A87C,
    0xFF5E9AC2, 0xFF4B88B7, 0xFF77ACCB,
    0xFFC06D4F, 0xFFAE5B42, 0xFFD08368,
    0xFF816097, 0xFF73578E, 0xFF9270A7,
    0xFF637F92, 0xFF557386, 0xFF7891A0
)

internal fun blockBackgroundArgb(startMinutes: Int, variant: Int): Long {
    val hour = (startMinutes / TimeSnapper.MinutesPerHour).coerceIn(0, 23)
    val period = (hour / 3 - 1).coerceAtLeast(0)
    return BlockColours[period * ColoursPerPeriod + variant.mod(ColoursPerPeriod)]
}
