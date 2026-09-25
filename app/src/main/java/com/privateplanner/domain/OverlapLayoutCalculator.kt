package com.privateplanner.domain

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
private val SingleColumnLayout = BlockLayout(columnIndex = 0, columnCount = 1)

object OverlapLayoutCalculator {
    fun calculate(blocks: List<PlannerBlock>): Map<Long, BlockLayout> {
        if (blocks.isEmpty()) return emptyMap()

        val sorted = blocks.sortedWith(BlockLayoutOrder)
        val result = HashMap<Long, BlockLayout>(blocks.size)
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

            assignCluster(sorted, clusterStart, index, result)
        }

        return result
    }

    private fun assignCluster(
        sorted: List<PlannerBlock>,
        startIndex: Int,
        endIndex: Int,
        result: MutableMap<Long, BlockLayout>
    ) {
        val size = endIndex - startIndex
        if (size == 1) {
            result[sorted[startIndex].id] = SingleColumnLayout
            return
        }
        val assignedColumns = IntArray(size)
        val columnEnds = IntArray(size)
        var columnCount = 0

        for (blockIndex in startIndex until endIndex) {
            val localIndex = blockIndex - startIndex
            val block = sorted[blockIndex]
            var column = 0
            while (column < columnCount && columnEnds[column] > block.startMinutes) {
                column += 1
            }
            if (column == columnCount) {
                columnCount += 1
            }

            assignedColumns[localIndex] = column
            columnEnds[column] = block.endMinutes
        }

        for (blockIndex in startIndex until endIndex) {
            val localIndex = blockIndex - startIndex
            val block = sorted[blockIndex]
            result[block.id] = BlockLayout(
                columnIndex = assignedColumns[localIndex],
                columnCount = columnCount
            )
        }
    }
}
