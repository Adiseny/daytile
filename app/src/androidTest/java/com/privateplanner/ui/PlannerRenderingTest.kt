package com.privateplanner.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentTimeBadgeStaysWithinTheGutter() {
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            PlannerTheme {
                CompositionLocalProvider(LocalCurrentMinuteOfDay provides mutableIntStateOf(60)) {
                    Box(Modifier.size(width = 300.dp, height = 240.dp)) {
                        CurrentTimeIndicator()
                    }
                }
            }
        }

        val badge = compose.onNode(hasContentDescription("Current time, 1:00"))
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Time badge width", 58f * density, badge.width, 1f)
        assertEquals("Time badge left inset", 6f * density, badge.left, 1f)
    }

    @Test
    fun backgroundMeasuredGridMatchesTheNormalMeasurementFallback() {
        val context = compose.activity.applicationContext
        // A density mismatch forces normal measurement inside composition.
        val differentDensity = Configuration(context.resources.configuration).apply {
            densityDpi += 80
        }
        runBlocking(Dispatchers.Default) {
            context.createConfigurationContext(differentDensity).prepareGridLabels()
        }
        val generation = mutableIntStateOf(0)
        compose.setContent {
            PlannerTheme {
                key(generation.intValue) {
                    Box(Modifier.size(width = 300.dp, height = 360.dp)) {
                        TimelineGrid(showsNow = false)
                    }
                }
            }
        }
        val fallback = compose.onRoot().captureToImage().toPixelMap()

        runBlocking(Dispatchers.Default) { context.prepareGridLabels() }
        compose.runOnUiThread { generation.intValue++ }
        val warmed = compose.onRoot().captureToImage().toPixelMap()

        assertEquals(fallback.width, warmed.width)
        assertEquals(fallback.height, warmed.height)
        for (y in 0 until fallback.height) {
            for (x in 0 until fallback.width) {
                assertEquals("Grid pixel at $x, $y", fallback[x, y], warmed[x, y])
            }
        }
    }
}
