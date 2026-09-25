package com.privateplanner.domain

import androidx.compose.runtime.Immutable
import java.time.LocalDate

const val MaxTitleLength = 120

@Immutable
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

// Primitive comparisons: `compareBy` would box every key it compares.
val PlannerBlockOrder = Comparator<PlannerBlock> { a, b ->
    if (a.startMinutes != b.startMinutes) a.startMinutes.compareTo(b.startMinutes) else a.id.compareTo(b.id)
}
