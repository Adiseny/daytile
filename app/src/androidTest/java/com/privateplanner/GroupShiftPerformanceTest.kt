package com.privateplanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.canShift
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupShiftPerformanceTest {
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
