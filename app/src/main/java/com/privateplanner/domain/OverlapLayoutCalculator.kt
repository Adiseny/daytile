package com.privateplanner.domain

class BlockLayout(
    val columnIndex: Int,
    val columnCount: Int
)

private val BlockLayoutOrder = compareBy<PlannerBlock> { it.startMinutes }
    .thenBy { it.endMinutes }
    .thenBy { it.id }

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
