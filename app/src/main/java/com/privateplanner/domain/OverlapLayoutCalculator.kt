package com.privateplanner.domain

// Shared immutable column assignments, so unchanged tiles need no new text layout.
data class BlockLayout(
    val columnIndex: Int,
    val columnCount: Int
) {
    // Short on purpose, as PlannerBlock's is.
    override fun toString() = "$columnIndex/$columnCount"
}

// A fixed-size map built once per layout. Only primitive ID lookup is needed: no
// deletion, iteration, resizing or general collection library. Null values mark empty
// slots, so every Long ID (including zero and negative legacy IDs) is supported.
class BlockLayouts internal constructor(blockCount: Int) {
    private val capacity = if (blockCount == 0) 1 else Integer.highestOneBit(blockCount * 2 - 1) shl 1
    private val keys = LongArray(capacity)
    private val values = arrayOfNulls<BlockLayout>(capacity)

    private fun slot(id: Long): Int = ((id xor (id ushr 32)).toInt() * -1640531527) and (capacity - 1)

    operator fun get(id: Long): BlockLayout? {
        var index = slot(id)
        while (true) {
            val value = values[index] ?: return null
            if (keys[index] == id) return value
            index = (index + 1) and (capacity - 1)
        }
    }

    internal operator fun set(id: Long, layout: BlockLayout) {
        var index = slot(id)
        while (values[index] != null && keys[index] != id) index = (index + 1) and (capacity - 1)
        keys[index] = id
        values[index] = layout
    }

    companion object {
        val Empty = BlockLayouts(0)
    }
}

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
    fun calculate(blocks: List<PlannerBlock>): BlockLayouts {
        if (blocks.isEmpty()) return BlockLayouts.Empty

        var sorted = blocks
        for (index in 1 until blocks.size) {
            if (BlockLayoutOrder.compare(blocks[index - 1], blocks[index]) > 0) {
                sorted = ArrayList(blocks).apply { sortWith(BlockLayoutOrder) }
                break
            }
        }
        // Primitive keys avoid a boxed Long and a map entry for every tile and lookup.
        val result = BlockLayouts(blocks.size)
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
