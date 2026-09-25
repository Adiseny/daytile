package com.privateplanner.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPaletteContrastTest {

    @Test
    fun blockColourPeriodsKeepTheirEstablishedBoundaries() {
        assertEquals(0xFF6F7772, blockBackgroundArgb(5 * 60 + 59, 0))
        assertEquals(0xFFC38A24, blockBackgroundArgb(6 * 60, 0))
        assertEquals(0xFF6F9B72, blockBackgroundArgb(9 * 60, 0))
        assertEquals(0xFF5E9AC2, blockBackgroundArgb(12 * 60, 0))
        assertEquals(0xFFC06D4F, blockBackgroundArgb(15 * 60, 0))
        assertEquals(0xFF816097, blockBackgroundArgb(18 * 60, 0))
        assertEquals(0xFF637F92, blockBackgroundArgb(21 * 60, 0))
    }

    @Test
    fun textContrastHoldsAtEveryMinuteOfTheDay() {
        forEachMinutePalette { minute, palette ->
            assertContrast(minute, "PrimaryText on Paper", palette.PrimaryText, palette.Paper, 7f)
            assertContrast(minute, "PrimaryText on Sheet", palette.PrimaryText, palette.Sheet, 7f)
            assertContrast(minute, "TimeText on Paper", palette.TimeText, palette.Paper, 4.5f)
            assertContrast(minute, "MutedText on Paper", palette.MutedText, palette.Paper, 4.5f)
            assertContrast(minute, "MutedText on Sheet", palette.MutedText, palette.Sheet, 4.5f)
            assertContrast(minute, "Delete on Paper", palette.Delete, palette.Paper, 4.5f)
            assertContrast(minute, "Delete on Sheet", palette.Delete, palette.Sheet, 4.5f)
            assertContrast(
                minute,
                "MutedText on AddButtonDisabled",
                palette.MutedText,
                palette.AddButtonDisabled,
                3f
            )
        }
    }

    @Test
    fun gridLinesStayVisibleAndKeepTheirHierarchyAtEveryMinute() {
        forEachMinutePalette { minute, palette ->
            val hour = contrast(palette.HourLine, palette.Paper)
            val halfHour = contrast(palette.HalfHourLine, palette.Paper)
            val quarter = contrast(palette.QuarterTick, palette.Paper)
            assertTrue("HourLine ratio $hour < 1.35 at minute $minute", hour >= 1.35f)
            assertTrue("HalfHourLine ratio $halfHour < 1.2 at minute $minute", halfHour >= 1.2f)
            assertTrue("QuarterTick ratio $quarter < 1.1 at minute $minute", quarter >= 1.1f)
            assertTrue(
                "Line hierarchy broken at minute $minute: $hour / $halfHour / $quarter",
                hour >= halfHour && halfHour >= quarter
            )
        }
    }

    @Test
    fun everyBlockLabelMeetsNormalTextContrastOnEveryTile() {
        forEachMinutePalette(step = 5) { minute, palette ->
            for (hour in 0 until 24) {
                for (variant in 0 until 3) {
                    val tile = Color(blockBackgroundArgb(hour * 60, variant))
                    for (active in listOf(false, true)) {
                        val surface = compositedTileBackground(tile, palette.Paper, active)
                        val ink = tileInkFor(tile, palette.Paper, active)
                        assertContrast(
                            minute,
                            "Tile ink hour=$hour variant=$variant active=$active",
                            ink,
                            surface,
                            4.5f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun paletteFollowsTheClock() {
        val night = paletteForMinute(3 * 60)
        val sunrise = paletteForMinute(7 * 60)
        val midday = paletteForMinute(13 * 60)
        val golden = paletteForMinute(19 * 60 + 30)
        val dusk = paletteForMinute(20 * 60)

        assertTrue("03:00 must be dark", !night.LightBackground && night.Paper.luminance() < 0.1f)
        assertTrue("07:00 must flip to light", sunrise.LightBackground)
        assertTrue("13:00 must be bright", midday.Paper.luminance() > 0.7f)
        assertTrue("20:00 must flip to dark", !dusk.LightBackground && dusk.Paper.luminance() < 0.1f)
        assertTrue(
            "The day must move through distinct light, not one static palette",
            sunrise.Paper != midday.Paper && midday.Paper != golden.Paper
        )
        assertTrue(
            "06:59 must still be dark right before the sunrise step",
            !paletteForMinute(6 * 60 + 59).LightBackground
        )
        assertTrue(
            "A ramp must actually move between its anchors",
            paletteForMinute(11 * 60).Paper != paletteForMinute(12 * 60).Paper
        )
    }

    private fun forEachMinutePalette(step: Int = 1, check: (Int, PlannerPalette) -> Unit) {
        for (minute in 0 until TimeSnapper.MinutesPerDay step step) {
            check(minute, paletteForMinute(minute))
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance() + 0.05f
        val second = b.luminance() + 0.05f
        return maxOf(first, second) / minOf(first, second)
    }

    private fun assertContrast(minute: Int, label: String, foreground: Color, background: Color, minimum: Float) {
        val ratio = contrast(foreground, background)
        assertTrue("$label ratio $ratio < $minimum at minute $minute", ratio >= minimum)
    }
}
