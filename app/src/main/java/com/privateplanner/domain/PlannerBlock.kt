package com.privateplanner.domain

import java.time.LocalDate

data class PlannerBlock(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val startMinutes: Int,
    val durationMinutes: Int
) {
    val endMinutes: Int
        get() = startMinutes + durationMinutes
}

val PlannerBlockOrder: Comparator<PlannerBlock> =
    compareBy<PlannerBlock> { it.startMinutes }.thenBy { it.id }
