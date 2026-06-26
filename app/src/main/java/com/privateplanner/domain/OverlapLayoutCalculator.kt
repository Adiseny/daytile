package com.privateplanner.domain

data class BlockLayout(
    val columnIndex: Int,
    val columnCount: Int
)

private val BlockLayoutOrder = compareBy<PlannerBlock> { it.startMinutes }.thenBy { it.endMinutes }

object OverlapLayoutCalculator {
    fun calculate(blocks: List<PlannerBlock>): Map<Long, BlockLayout> {
        if (blocks.isEmpty()) return emptyMap()

        val sorted = blocks.sortedWith(BlockLayoutOrder)
        val result = HashMap<Long, BlockLayout>(blocks.size)
        var index = 0

        while (index < sorted.size) {
            val clusterStart = index
            var clusterEnd = sorted[index].endMinutes

            while (index < sorted.size && (index == clusterStart || sorted[index].startMinutes < clusterEnd)) {
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
        val activeColumns = IntArray(size)
        val activeEnds = IntArray(size)
        val usedColumns = IntArray(size)
        val assignedColumns = IntArray(size)
        var activeCount = 0
        var maxActive = 1
        var marker = 1

        for (blockIndex in startIndex until endIndex) {
            val localIndex = blockIndex - startIndex
            val block = sorted[blockIndex]
            var kept = 0
            for (activeIndex in 0 until activeCount) {
                if (activeEnds[activeIndex] > block.startMinutes) {
                    activeColumns[kept] = activeColumns[activeIndex]
                    activeEnds[kept] = activeEnds[activeIndex]
                    kept += 1
                }
            }
            activeCount = kept

            for (activeIndex in 0 until activeCount) {
                usedColumns[activeColumns[activeIndex]] = marker
            }

            var column = 0
            while (usedColumns[column] == marker) {
                column += 1
            }
            marker += 1

            assignedColumns[localIndex] = column
            activeColumns[activeCount] = column
            activeEnds[activeCount] = block.endMinutes
            activeCount += 1
            maxActive = maxOf(maxActive, activeCount)
        }

        for (blockIndex in startIndex until endIndex) {
            val localIndex = blockIndex - startIndex
            val block = sorted[blockIndex]
            result[block.id] = BlockLayout(
                columnIndex = assignedColumns[localIndex],
                columnCount = maxActive
            )
        }
    }
}
