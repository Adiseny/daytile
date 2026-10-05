package com.privateplanner.ui

import com.privateplanner.domain.TimeSnapper
import kotlin.math.cbrt
import kotlin.math.pow

// Colours are ARGB ints throughout.
internal class PlannerPalette(
    val Paper: Int,
    val Sheet: Int,
    val PrimaryText: Int,
    val MutedText: Int,
    val TimeText: Int,
    val HourLine: Int,
    val HalfHourLine: Int,
    val QuarterTick: Int,
    val AddButtonDisabled: Int,
    val Delete: Int,
    val Scrim: Int,
    val LightBackground: Boolean
)

private fun channel(value: Float): Int = (value.coerceAtLeast(0f).coerceAtMost(1f) * 255f + 0.5f).toInt()

internal fun withAlpha(colour: Int, alpha: Float): Int = (colour and 0x00FFFFFF) or (channel(alpha) shl 24)

// Source over, on the stored (gamma-encoded) values.
internal fun compositeOver(foreground: Int, background: Int): Int {
    val fgA = (foreground ushr 24) / 255f
    val bgA = (background ushr 24) / 255f
    val a = fgA + bgA * (1f - fgA)
    fun mix(shift: Int): Int {
        if (a == 0f) return 0
        val fg = (foreground ushr shift and 0xFF) / 255f
        val bg = (background ushr shift and 0xFF) / 255f
        return channel((fg * fgA + bg * bgA * (1f - fgA)) / a)
    }
    return (channel(a) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

private fun linear(channel: Int): Double {
    val x = channel / 255.0
    return if (x >= 0.04045) ((x + 0.055) / 1.055).pow(2.4) else x / 12.92
}

private fun encoded(linear: Double): Int =
    channel((if (linear >= 0.0031308) 1.055 * linear.pow(1 / 2.4) - 0.055 else linear * 12.92).toFloat())

// Relative luminance, as contrast ratios are defined on.
internal fun luminance(colour: Int): Float =
    (0.2126 * linear(colour ushr 16 and 0xFF) + 0.7152 * linear(colour ushr 8 and 0xFF) + 0.0722 * linear(colour and 0xFF))
        .toFloat().coerceAtLeast(0f).coerceAtMost(1f)

// A straight line through Oklab, where equal steps look equal: the space the palettes
// were tuned in.
internal fun lerp(from: Int, to: Int, fraction: Float): Int {
    val t = fraction.coerceAtLeast(0f).coerceAtMost(1f).toDouble()
    fun lab(colour: Int): DoubleArray {
        val r = linear(colour ushr 16 and 0xFF)
        val g = linear(colour ushr 8 and 0xFF)
        val b = linear(colour and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }
    val a = lab(from)
    val b = lab(to)
    val lightness = (1 - t) * a[0] + t * b[0]
    val greenRed = (1 - t) * a[1] + t * b[1]
    val blueYellow = (1 - t) * a[2] + t * b[2]
    val l = (lightness + 0.3963377774 * greenRed + 0.2158037573 * blueYellow).pow(3)
    val m = (lightness - 0.1055613458 * greenRed - 0.0638541728 * blueYellow).pow(3)
    val s = (lightness - 0.0894841775 * greenRed - 1.2914855480 * blueYellow).pow(3)
    val alpha = channel(((1 - t) * (from ushr 24) + t * (to ushr 24)).toFloat() / 255f)
    return (alpha shl 24) or
        (encoded(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s) shl 16) or
        (encoded(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s) shl 8) or
        encoded(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
}

// The planner follows the clock, not the system theme: light through the day,
// dark at night, with slow ramps between hand-designed anchors and two
// deliberate steps at sunrise and dusk where the ink polarity flips (a ramp
// across a polarity flip would pass text and paper through unreadable greys).
// Ink sets are fixed per polarity so contrast cannot drift mid-ramp;
// PlannerPaletteContrastTest asserts the ratios for every minute of the day.

private const val HourLineBlend = 0.42f
private const val HalfHourLineBlend = 0.34f
private const val QuarterTickBlend = 0.26f

private const val Opaque = 0xFF000000.toInt()

// An anchor's four colours are given as RGB and are opaque.
private fun palette(light: Boolean, paper: Int, sheet: Int, lineInk: Int, timeText: Int) = PlannerPalette(
    Paper = paper or Opaque,
    Sheet = sheet or Opaque,
    PrimaryText = if (light) 0xFF1A1814.toInt() else 0xFFEFE7DA.toInt(),
    MutedText = if (light) 0xFF675E51.toInt() else 0xFF9C9384.toInt(),
    TimeText = timeText or Opaque,
    HourLine = lerp(paper or Opaque, lineInk or Opaque, HourLineBlend),
    HalfHourLine = lerp(paper or Opaque, lineInk or Opaque, HalfHourLineBlend),
    QuarterTick = lerp(paper or Opaque, lineInk or Opaque, QuarterTickBlend),
    AddButtonDisabled = if (light) 0xFFE0D8CD.toInt() else 0xFF39322A.toInt(),
    Delete = if (light) 0xFF9B4F45.toInt() else 0xFFC9756A.toInt(),
    Scrim = if (light) 0x661A1814 else 0xAA0B0906.toInt(),
    LightBackground = light
)

private val NightPalette = palette(false, 0x15120D, 0x221C15, 0x6F5B3E, 0xC6BBA8)
private val MiddayPalette = palette(true, 0xF6F2EC, 0xFFF9F1, 0x574A38, 0x3F3932)

// minute = when this stop is fully reached. ramp = fade from the previous stop
// across the segment; otherwise the previous palette holds and the theme steps
// here in one minute. Polarity may only change on a step, never on a ramp.
private class DaylightStop(val minute: Int, val ramp: Boolean, val palette: PlannerPalette)

private val DaylightStops = listOf(
    DaylightStop(0, false, NightPalette),
    DaylightStop(5 * 60, false, NightPalette),
    // First light: the night sky warms before sunrise.
    DaylightStop(6 * 60 + 45, true, palette(false, 0x1C1710, 0x291F15, 0x7D6440, 0xCDBFA4)),
    // Sunrise: polarity flips to warm morning light.
    DaylightStop(7 * 60, false, palette(true, 0xF3E6D0, 0xFCF1DE, 0x6E5730, 0x4A3B24)),
    DaylightStop(9 * 60 + 30, true, palette(true, 0xF5EDDE, 0xFEF6E8, 0x625234, 0x443A28)),
    DaylightStop(13 * 60, true, MiddayPalette),
    DaylightStop(16 * 60 + 30, true, palette(true, 0xF6EEDD, 0xFEF5E5, 0x64522F, 0x463B26)),
    // Golden hour: the warmest light of the day.
    DaylightStop(19 * 60 + 30, true, palette(true, 0xF1E2C8, 0xFAEDD6, 0x6E5426, 0x4B3B1F)),
    // Dusk: polarity flips back to a warm dark evening.
    DaylightStop(20 * 60, false, palette(false, 0x1F1810, 0x2D2214, 0x876A3E, 0xD2C2A2)),
    DaylightStop(22 * 60 + 30, true, NightPalette)
)

// The palette changes at most once per step.
internal const val PaletteStepMinutes = 5

internal fun paletteForMinute(minuteOfDay: Int): PlannerPalette {
    val minute = minuteOfDay.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay - 1)
    val index = DaylightStops.indexOfLast { it.minute <= minute }
    val current = DaylightStops[index]
    val next = DaylightStops.getOrNull(index + 1)
    if (next == null || !next.ramp) return current.palette
    val fraction = (minute - current.minute).toFloat() / (next.minute - current.minute)
    val from = current.palette
    val to = next.palette
    return PlannerPalette(
        Paper = lerp(from.Paper, to.Paper, fraction),
        Sheet = lerp(from.Sheet, to.Sheet, fraction),
        // Ramps never cross an ink-polarity boundary, so these are the same at both ends.
        PrimaryText = from.PrimaryText,
        MutedText = from.MutedText,
        TimeText = lerp(from.TimeText, to.TimeText, fraction),
        HourLine = lerp(from.HourLine, to.HourLine, fraction),
        HalfHourLine = lerp(from.HalfHourLine, to.HalfHourLine, fraction),
        QuarterTick = lerp(from.QuarterTick, to.QuarterTick, fraction),
        AddButtonDisabled = from.AddButtonDisabled,
        Delete = from.Delete,
        Scrim = from.Scrim,
        LightBackground = from.LightBackground
    )
}

// The palette on display with its step, as a stop of its own. Shared with the launch warm-up
// thread, which works out the first one.
@Volatile
private var displayed: DaylightStop? = null

internal fun displayedPaletteForMinute(minuteOfDay: Int): PlannerPalette {
    val minute = minuteOfDay.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay - 1)
    val step = minute - minute % PaletteStepMinutes
    return displayed?.takeIf { it.minute == step }?.palette
        ?: paletteForMinute(step).also { displayed = DaylightStop(step, false, it) }
}
