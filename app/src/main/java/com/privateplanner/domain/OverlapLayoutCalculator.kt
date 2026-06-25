package com.privateplanner.domain

data class BlockLayout(
    val columnIndex: Int,
    val columnCount: Int
)

object OverlapLayoutCalculator {
    fun calculate(blocks: List<PlannerBlock>): Map<Long, BlockLayout> {
        if (blocks.isEmpty()) return emptyMap()

        val sorted = blocks.sortedWith(compareBy<PlannerBlock> { it.startMinutes }.thenBy { it.endMinutes })
        val result = mutableMapOf<Long, BlockLayout>()
        var index = 0

        while (index < sorted.size) {
            val cluster = mutableListOf<PlannerBlock>()
            var clusterEnd = sorted[index].endMinutes

            while (index < sorted.size && (cluster.isEmpty() || sorted[index].startMinutes < clusterEnd)) {
                val block = sorted[index]
                cluster += block
                clusterEnd = maxOf(clusterEnd, block.endMinutes)
                index += 1
            }

            assignCluster(cluster, result)
        }

        return result
    }

    private fun assignCluster(
        cluster: List<PlannerBlock>,
        result: MutableMap<Long, BlockLayout>
    ) {
        val active = mutableListOf<Pair<Int, PlannerBlock>>()
        val assignments = mutableMapOf<Long, Int>()
        var maxActive = 1

        cluster.forEach { block ->
            active.removeAll { (_, activeBlock) -> activeBlock.endMinutes <= block.startMinutes }
            val usedColumns = active.map { it.first }.toSet()
            val column = generateSequence(0) { it + 1 }.first { it !in usedColumns }
            assignments[block.id] = column
            active += column to block
            maxActive = maxOf(maxActive, active.size)
        }

        cluster.forEach { block ->
            result[block.id] = BlockLayout(
                columnIndex = assignments.getValue(block.id),
                columnCount = maxActive
            )
        }
    }
}
