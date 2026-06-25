package com.privateplanner.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = BenchmarkIterations,
        setupBlock = {
            pressHome()
        }
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun timelineScrollFrames() = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = BenchmarkIterations,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        val centerX = device.displayWidth / 2
        val topY = device.displayHeight / 4
        val bottomY = device.displayHeight * 3 / 4

        repeat(3) {
            device.swipe(centerX, bottomY, centerX, topY, 24)
            device.waitForIdle()
            device.swipe(centerX, topY, centerX, bottomY, 24)
            device.waitForIdle()
        }
    }

    @Test
    fun timeBlockDragFrames() = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = BenchmarkIterations,
        setupBlock = {
            device.executeShellCommand("pm clear $TargetPackage")
            pressHome()
            startActivityAndWait()
            createBenchmarkBlock()
        }
    ) {
        val bounds = benchmarkBlockBounds()
        val startX = bounds.centerX()
        val startY = bounds.centerY()
        val endY = dragEndY(startY)

        device.executeShellCommand("input touchscreen motionevent DOWN $startX $startY")
        Thread.sleep(DragHoldMillis)
        try {
            performDragMove(startX, startY, endY)
        } finally {
            device.executeShellCommand("input touchscreen motionevent UP $startX $endY")
        }
        device.waitForIdle()
    }

    @Test
    fun heldTimeBlockDragFrames() {
        var startX = 0
        var startY = 0
        var endY = 0

        benchmarkRule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.WARM,
            iterations = BenchmarkIterations,
            setupBlock = {
                device.executeShellCommand("pm clear $TargetPackage")
                pressHome()
                startActivityAndWait()
                createBenchmarkBlock()

                val bounds = benchmarkBlockBounds()
                startX = bounds.centerX()
                startY = bounds.centerY()
                endY = dragEndY(startY)
                device.executeShellCommand("input touchscreen motionevent DOWN $startX $startY")
                Thread.sleep(HeldDragSetupHoldMillis)
            }
        ) {
            try {
                performDragMove(startX, startY, endY)
            } finally {
                device.executeShellCommand("input touchscreen motionevent UP $startX $endY")
            }
            device.waitForIdle()
        }
    }
}

private fun MacrobenchmarkScope.createBenchmarkBlock() {
    device.wait(Until.hasObject(By.textContains("Today")), UiWaitMillis)
    device.click(device.displayWidth / 2, device.displayHeight / 2)
    device.wait(Until.hasObject(By.text("What's happening?")), UiWaitMillis)
    device.executeShellCommand("input text $BenchmarkBlockTitle")
    device.pressBack()
    val addButton = device.wait(Until.findObject(By.text("Add")), UiWaitMillis)
        ?: error("Add button was not visible")
    addButton.click()
    device.wait(Until.hasObject(By.descContains(BenchmarkBlockTitle)), UiWaitMillis)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.benchmarkBlockBounds(): Rect {
    val block = device.wait(
        Until.findObject(By.descContains(BenchmarkBlockTitle)),
        UiWaitMillis
    ) ?: error("Benchmark block was not visible")
    return block.visibleBounds
}

private fun MacrobenchmarkScope.dragEndY(startY: Int): Int {
    return (startY + device.displayHeight / 5)
        .coerceAtMost(device.displayHeight - DragBottomMarginPx)
}

private fun MacrobenchmarkScope.performDragMove(startX: Int, startY: Int, endY: Int) {
    repeat(DragSteps) { index ->
        val fraction = (index + 1).toFloat() / DragSteps
        val y = (startY + (endY - startY) * fraction).toInt()
        device.executeShellCommand("input touchscreen motionevent MOVE $startX $y")
        Thread.sleep(DragStepDelayMillis)
    }
}

private const val TargetPackage = "com.privateplanner"
private const val BenchmarkIterations = 5
private const val BenchmarkBlockTitle = "BenchDrag"
private const val UiWaitMillis = 5_000L
private const val DragHoldMillis = 375L
private const val HeldDragSetupHoldMillis = 450L
private const val DragSteps = 36
private const val DragStepDelayMillis = 8L
private const val DragBottomMarginPx = 240
