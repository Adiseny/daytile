package com.privateplanner.domain

object TimeOfDayColourMapper {
    private val Night = longArrayOf(0xFF6F7772, 0xFF64717A, 0xFF7B7068)
    private val Early = longArrayOf(0xFFC38A24, 0xFFB97820, 0xFFD49D35)
    private val Morning = longArrayOf(0xFF6F9B72, 0xFF5F8D68, 0xFF84A87C)
    private val Midday = longArrayOf(0xFF5E9AC2, 0xFF4B88B7, 0xFF77ACCB)
    private val Afternoon = longArrayOf(0xFFC06D4F, 0xFFAE5B42, 0xFFD08368)
    private val Evening = longArrayOf(0xFF816097, 0xFF73578E, 0xFF9270A7)
    private val Late = longArrayOf(0xFF637F92, 0xFF557386, 0xFF7891A0)

    fun backgroundArgb(startMinutes: Int, variant: Int): Long {
        val hour = (startMinutes / TimeSnapper.MinutesPerHour).coerceIn(0, 23)
        val palette = when (hour) {
            in 0 until 6 -> Night
            in 6 until 9 -> Early
            in 9 until 12 -> Morning
            in 12 until 15 -> Midday
            in 15 until 18 -> Afternoon
            in 18 until 21 -> Evening
            else -> Late
        }
        return palette[variant.mod(palette.size)]
    }
}
