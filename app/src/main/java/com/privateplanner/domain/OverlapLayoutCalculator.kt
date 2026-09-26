package com.privateplanner.domain

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.emptyLongObjectMap

// Compared by value, so a tile whose column is unchanged skips recomposition when
// another block moves.
data class BlockLayout(
    val columnIndex: Int,
    val columnCount: Int
)

private val BlockLayoutOrder = Comparator<PlannerBlock> { a, b ->
    when {
        a.startMinutes != b.startMinutes -> a.startMinutes.compareTo(b.startMinutes)
        a.endMinutes != b.endMinutes -> a.endMinutes.compareTo(b.endMinutes)
        else -> a.id.compareTo(b.id)
    }
}
// All normal layouts are shared: a dense day needs at most 36 of these values.
private val ColumnLayouts = Array(OverlapPolicy.MaxTransientOverlap) { count ->
    Array(count + 1) { column -> BlockLayout(column, count + 1) }
}
private val EmptyColumns = IntArray(0)

object OverlapLayoutCalculator {
    fun calculate(blocks: List<PlannerBlock>): LongObjectMap<BlockLayout> {
        if (blocks.isEmpty()) return emptyLongObjectMap()

        val sorted = if ((1 until blocks.size).all { BlockLayoutOrder.compare(blocks[it - 1], blocks[it]) <= 0 }) {
            blocks
        } else {
            blocks.sortedWith(BlockLayoutOrder)
        }
        // Primitive keys avoid a boxed Long and a map entry for every tile and lookup.
        val result = MutableLongObjectMap<BlockLayout>(blocks.size)
        // Most days need no scratch space; overlapping clusters reuse the same buffers.
        var assignedColumns = EmptyColumns
        var columnEnds = EmptyColumns
        var index = 0

        while (index < sorted.size) {
            val clusterStart = index
            var clusterEnd = sorted[index].endMinutes
            index += 1

            while (index < sorted.size && sorted[index].startMinutes < clusterEnd) {
                val block = sorted[index]
                clusterEnd = maxOf(clusterEnd, block.endMinutes)
                index += 1
            }

            val size = index - clusterStart
            if (size == 1) {
                result[sorted[clusterStart].id] = ColumnLayouts[0][0]
                continue
            }
            if (assignedColumns.size < size) assignedColumns = IntArray(size)
            if (columnEnds.isEmpty()) columnEnds = IntArray(minOf(size, OverlapPolicy.MaxSavedOverlap))
            var columnCount = 0

            for (blockIndex in clusterStart until index) {
                val block = sorted[blockIndex]
                var column = 0
                while (column < columnCount && columnEnds[column] > block.startMinutes) column++
                if (column == columnCount) {
                    if (column == columnEnds.size) columnEnds = columnEnds.copyOf(columnCount * 2)
                    columnCount++
                }
                assignedColumns[blockIndex - clusterStart] = column
                columnEnds[column] = block.endMinutes
            }

            for (blockIndex in clusterStart until index) {
                val column = assignedColumns[blockIndex - clusterStart]
                result[sorted[blockIndex].id] = if (columnCount <= ColumnLayouts.size) {
                    ColumnLayouts[columnCount - 1][column]
                } else {
                    BlockLayout(columnIndex = column, columnCount = columnCount)
                }
            }
        }

        return result
    }
}
