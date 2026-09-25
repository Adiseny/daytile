package com.privateplanner.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    @Test
    fun oneNodeTileDrawsLikeATouchBoxAroundAVisualTile() {
        // A short tile inside a larger touch target, and a tall one showing its duration.
        val cases = listOf(Triple(48, 14, 20), Triple(150, 0, 150))
        val case = mutableIntStateOf(0)
        val merged = mutableStateOf(false)
        compose.setContent {
            PlannerTheme {
                Box(Modifier.size(width = 240.dp, height = 220.dp)) {
                    val (touch, offset, visual) = cases[case.intValue]
                    val place = Modifier.offset(x = 12.dp, y = 30.dp).width(180.dp).height(touch.dp)
                    if (merged.value) {
                        TestTile(
                            visual,
                            place.wrapContentHeight(Alignment.Top).offset(y = offset.dp).height(visual.dp)
                        )
                    } else {
                        Box(place) {
                            TestTile(visual, Modifier.offset(y = offset.dp).fillMaxWidth().height(visual.dp))
                        }
                    }
                }
            }
        }
        for (index in cases.indices) {
            compose.runOnUiThread {
                case.intValue = index
                merged.value = false
            }
            val nested = compose.onRoot().captureToImage().toPixelMap()
            compose.runOnUiThread { merged.value = true }
            val single = compose.onRoot().captureToImage().toPixelMap()
            for (y in 0 until nested.height) {
                for (x in 0 until nested.width) {
                    assertEquals("Tile case $index pixel at $x, $y", nested[x, y], single[x, y])
                }
            }
        }
    }

    @Composable
    private fun TestTile(visual: Int, modifier: Modifier) {
        TimeBlockForeground(
            background = Color(0xFF5E9AC2),
            shape = RoundedCornerShape(if (visual < 48) 13.dp else 16.dp),
            active = false,
            title = "Focus",
            rangeText = { "9:00 \u2013 9:10" },
            durationText = "10m",
            tileWidth = 180.dp,
            visualHeight = visual.dp,
            titleFollowOffset = null,
            modifier = modifier
        )
    }
}
