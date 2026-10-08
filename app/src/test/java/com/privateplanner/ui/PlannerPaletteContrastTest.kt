package com.privateplanner.ui

import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PlannerPaletteContrastTest {

    @Test
    fun contrastReferenceMatchesPrimaries() {
        assertEquals(0f, luminance(0xFF000000.toInt()), 0.000001f)
        assertEquals(1f, luminance(0xFFFFFFFF.toInt()), 0.000001f)
        assertEquals(0.2126f, luminance(0xFFFF0000.toInt()), 0.000001f)
        assertEquals(0.7152f, luminance(0xFF00FF00.toInt()), 0.000001f)
        assertEquals(0.0722f, luminance(0xFF0000FF.toInt()), 0.000001f)
    }

    @Test
    fun blockColourPeriodsKeepTheirEstablishedBoundaries() {
        assertEquals(0xFF6F7772.toInt(), blockBackgroundArgb(5 * 60 + 59, 0))
        assertEquals(0xFFC38A24.toInt(), blockBackgroundArgb(6 * 60, 0))
        assertEquals(0xFF6F9B72.toInt(), blockBackgroundArgb(9 * 60, 0))
        assertEquals(0xFF5E9AC2.toInt(), blockBackgroundArgb(12 * 60, 0))
        assertEquals(0xFFC06D4F.toInt(), blockBackgroundArgb(15 * 60, 0))
        assertEquals(0xFF816097.toInt(), blockBackgroundArgb(18 * 60, 0))
        assertEquals(0xFF637F92.toInt(), blockBackgroundArgb(21 * 60, 0))
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
        forEachMinutePalette { minute, palette ->
            for (hour in 0 until 24) {
                for (variant in 0 until 3) {
                    val tile = blockBackgroundArgb(hour * 60, variant)
                    for (active in listOf(false, true)) {
                        val surface = compositedTileBackground(tile, palette, active)
                        val ink = tileInkFor(palette)
                        assertEquals(if (palette.LightBackground) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), ink)
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

    // The week stands its blocks on bands of their own colour. A band must show against the
    // paper at every hour of the day's changing light, and a tile, which is the colour and
    // the ink the day gives it, must show against its band.
    @Test
    fun weekBandsShowOnThePaperAndTilesShowOnTheirBandsAtEveryMinute() {
        forEachMinutePalette { minute, palette ->
            for (hour in 0 until 24) {
                val band = bandColour(hour * 60, palette.Paper)
                val shows = contrast(band, palette.Paper)
                assertTrue("Band of $hour:00 ratio $shows < 1.1 at minute $minute", shows >= 1.1f)
                for (variant in 0 until 3) {
                    val tile = blockBackgroundArgb(hour * 60, variant)
                    val solid = compositedTileBackground(tile, palette, false)
                    val stands = contrast(solid, band)
                    assertTrue("Tile of $hour:00 variant $variant ratio $stands < 1.5 on its band at minute $minute", stands >= 1.5f)
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

        assertTrue("03:00 must be dark", !night.LightBackground && luminance(night.Paper) < 0.1f)
        assertTrue("07:00 must flip to light", sunrise.LightBackground)
        assertTrue("13:00 must be bright", luminance(midday.Paper) > 0.7f)
        assertTrue("20:00 must flip to dark", !dusk.LightBackground && luminance(dusk.Paper) < 0.1f)
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

    private fun forEachMinutePalette(check: (Int, PlannerPalette) -> Unit) {
        for (minute in 0 until TimeSnapper.MinutesPerDay) {
            check(minute, paletteForMinute(minute))
        }
    }

    // Independent sRGB reference; production drawing needs no luminance calculation.
    private fun luminance(colour: Int): Float {
        fun linear(shift: Int): Double {
            val value = (colour ushr shift and 255) / 255.0
            return if (value < 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return (0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)).toFloat()
    }

    private fun contrast(a: Int, b: Int): Float {
        val first = luminance(a) + 0.05f
        val second = luminance(b) + 0.05f
        return maxOf(first, second) / minOf(first, second)
    }

    private fun assertContrast(minute: Int, label: String, foreground: Int, background: Int, minimum: Float) {
        val ratio = contrast(foreground, background)
        assertTrue("$label ratio $ratio < $minimum at minute $minute", ratio >= minimum)
    }
}
