package com.privateplanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateplanner.data.PlannerBlockDao
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.data.PlannerWriteResult
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.canShift
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupShiftPerformanceTest {
    @Test fun measureCrowdedGroupSavesAgainstThePreviousDayScan() {
        measureGroupSaves(null)
    }

    @Test fun measureCrowdedGroupSavesWithDiskCommits() {
        measureGroupSaves("group-save-audit-${System.nanoTime()}.db")
    }

    private fun measureGroupSaves(name: String?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = PlannerDatabase(context, name)
        val date = LocalDate.of(2026, 10, 8)
        val blocks = (120 until 1200 step 15).flatMap { start -> List(7) { column ->
            PlannerBlock(id = (start * 7 + column).toLong(), date = date,
                title = "Task $start/$column", startMinutes = start, durationMinutes = 15)
        } }
        // The original group save addressed each selected task by day and ID alone.
        val previous = PlannerRepository(object : PlannerBlockDao by database {
            override fun moveBlock(block: PlannerBlock, toEpochDay: Long, startMinutes: Int, durationMinutes: Int): Int {
                assertEquals(block.date.toEpochDay(), toEpochDay)
                return database.updateTime(toEpochDay, block.id, startMinutes, durationMinutes)
            }
        })
        val current = PlannerRepository(database)
        val ids = blocks.map { it.id }.toLongArray()
        fun move(repository: PlannerRepository, delta: Int) {
            assertEquals(PlannerWriteResult.Success, repository.shiftBlocks(date, ids, delta))
        }
        fun sample(repository: PlannerRepository): Long {
            val start = System.nanoTime()
            repeat(5) { move(repository, 5); move(repository, -5) }
            return (System.nanoTime() - start) / 10
        }
        try {
            database.withTransaction { blocks.forEach { database.insertBlock(it) } }
            repeat(5) { sample(previous); sample(current) }
            val before = ArrayList<Long>()
            val after = ArrayList<Long>()
            repeat(7) {
                if (it % 2 == 0) { before += sample(previous); after += sample(current) }
                else { after += sample(current); before += sample(previous) }
                assertEquals("Every stored field must survive both save paths", blocks, database.getBlocksForDate(date.toEpochDay()))
            }
            val storage = if (name == null) "in-memory" else "file-backed"
            File(context.cacheDir, if (name == null) "group-save-performance.txt" else "group-save-disk-performance.txt").writeText(
                "504 selected tasks, ns per complete repository save, $storage Android SQLite\n" +
                    "previous=$before\ncurrent=$after\nmedian previous=${before.sorted()[3]} current=${after.sorted()[3]}\n"
            )
        } finally {
            database.close()
            if (name != null) {
                File(context.dataDir, name).delete()
                File(context.dataDir, "$name-journal").delete()
            }
        }
    }

    @Test fun measureValidCrowdedGroupStepsOnAndroid() {
        val blocks = (120 until 1200 step 15).flatMap { start -> List(7) { column ->
            PlannerBlock(id = (start * 7 + column).toLong(), date = LocalDate.of(2026, 10, 8),
                title = "Task", startMinutes = start, durationMinutes = 15)
        } }
        val ids = blocks.map { it.id }.toLongArray()
        fun previous(): Boolean {
            val shifted = blocks.map { if (it.id in ids) it.copy(startMinutes = it.startMinutes + 5) else it }
            return shifted.all { it.id !in ids || OverlapPolicy.from(shifted, it.id).canPlace(it.startMinutes, it.durationMinutes) }
        }
        fun samples(action: () -> Boolean): List<Long> = List(7) {
            val start = System.nanoTime()
            repeat(10) { assertTrue(action()) }
            (System.nanoTime() - start) / 10
        }
        repeat(20) { assertTrue(previous()); assertTrue(canShift(blocks, ids, 5)) }
        val before = samples { previous() }
        val after = samples { canShift(blocks, ids, 5) }
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "group-performance.txt")
            .writeText("504 selected tasks, ns per valid group step\nprevious=$before\ncurrent=$after\n" +
                "median previous=${before.sorted()[3]} current=${after.sorted()[3]}\n")
    }
}
