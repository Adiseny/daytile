package com.privateplanner.domain

private const val ColoursPerPeriod = 3
private val BlockColours = intArrayOf(
    0x6F7772, 0x64717A, 0x7B7068,
    0xC38A24, 0xB97820, 0xD49D35,
    0x6F9B72, 0x5F8D68, 0x84A87C,
    0x5E9AC2, 0x4B88B7, 0x77ACCB,
    0xC06D4F, 0xAE5B42, 0xD08368,
    0x816097, 0x73578E, 0x9270A7,
    0x637F92, 0x557386, 0x7891A0
)

internal fun blockBackgroundArgb(startMinutes: Int, variant: Int): Int {
    val hour = (startMinutes / TimeSnapper.MinutesPerHour).coerceAtLeast(0).coerceAtMost(23)
    val period = (hour / 3 - 1).coerceAtLeast(0)
    return BlockColours[period * ColoursPerPeriod + variant.mod(ColoursPerPeriod)] or 0xFF000000.toInt()
}
