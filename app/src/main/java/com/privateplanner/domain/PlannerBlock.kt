package com.privateplanner.domain

import java.time.LocalDate

const val MaxTitleLength = 120

// A title of nothing but spaces is no title.
fun CharSequence?.isBlankTitle(): Boolean = this == null || all { it.isWhitespace() }

data class PlannerBlock(
    val id: Long = 0,
    val date: LocalDate,
    val title: String,
    val startMinutes: Int,
    val durationMinutes: Int
) {
    val endMinutes: Int
        get() = startMinutes + durationMinutes

    // Short on purpose: the generated one would ship the name of every property.
    override fun toString() = "$id $title"
}

// Primitive comparisons: `compareBy` would box every key it compares.
val PlannerBlockOrder = Comparator<PlannerBlock> { a, b ->
    if (a.startMinutes != b.startMinutes) a.startMinutes.compareTo(b.startMinutes) else a.id.compareTo(b.id)
}
