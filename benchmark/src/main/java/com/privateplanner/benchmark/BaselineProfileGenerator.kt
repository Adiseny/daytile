package com.privateplanner.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TargetPackage,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()

        val centerX = device.displayWidth / 2
        val topY = device.displayHeight / 4
        val bottomY = device.displayHeight * 3 / 4

        repeat(2) {
            device.swipe(centerX, bottomY, centerX, topY, 24)
            device.waitForIdle()
            device.swipe(centerX, topY, centerX, bottomY, 24)
            device.waitForIdle()
        }
    }
}

private const val TargetPackage = "com.privateplanner"
