package com.privateplanner.domain

import java.time.LocalDate

// A title edit leaves every overlap column in place, in either view.
internal fun sameBlockTimes(before: List<PlannerBlock>?, after: List<PlannerBlock>): Boolean {
    if (before == null || before.size != after.size) return false
    for (index in after.indices) {
        val a = before[index]
        val b = after[index]
        if (a.id != b.id || a.startMinutes != b.startMinutes || a.durationMinutes != b.durationMinutes) return false
    }
    return true
}

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
