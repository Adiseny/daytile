package com.privateplanner.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import kotlinx.coroutines.delay

// Night paper, also the dark scrim behind light system-bar icons.
private const val PaperBackgroundDarkArgb: Int = 0xFF15120D.toInt()

internal class PlannerPalette(
    val Paper: Color,
    val Sheet: Color,
    val PrimaryText: Color,
    val MutedText: Color,
    val TimeText: Color,
    val HourLine: Color,
    val HalfHourLine: Color,
    val QuarterTick: Color,
    val AddButtonDisabled: Color,
    val Delete: Color,
    val Scrim: Color,
    val LightBackground: Boolean
)

// The planner follows the clock, not the system theme: light through the day,
// dark at night, with slow ramps between hand-designed anchors and two
// deliberate steps at sunrise and dusk where the ink polarity flips (a ramp
// across a polarity flip would pass text and paper through unreadable greys).
// Ink sets are fixed per polarity so contrast cannot drift mid-ramp;
// PlannerPaletteContrastTest asserts the ratios for every minute of the day.

private const val HourLineBlend = 0.42f
private const val HalfHourLineBlend = 0.34f
private const val QuarterTickBlend = 0.26f

private fun dayPalette(paper: Color, sheet: Color, lineInk: Color, timeText: Color): PlannerPalette {
    return PlannerPalette(
        Paper = paper,
        Sheet = sheet,
        PrimaryText = Color(0xFF1A1814),
        MutedText = Color(0xFF675E51),
        TimeText = timeText,
        HourLine = lerp(paper, lineInk, HourLineBlend),
        HalfHourLine = lerp(paper, lineInk, HalfHourLineBlend),
        QuarterTick = lerp(paper, lineInk, QuarterTickBlend),
        AddButtonDisabled = Color(0xFFE0D8CD),
        Delete = Color(0xFF9B4F45),
        Scrim = Color(0x661A1814),
        LightBackground = true
    )
}

private fun nightPalette(paper: Color, sheet: Color, lineInk: Color, timeText: Color): PlannerPalette {
    return PlannerPalette(
        Paper = paper,
        Sheet = sheet,
        PrimaryText = Color(0xFFEFE7DA),
        MutedText = Color(0xFF9C9384),
        TimeText = timeText,
        HourLine = lerp(paper, lineInk, HourLineBlend),
        HalfHourLine = lerp(paper, lineInk, HalfHourLineBlend),
        QuarterTick = lerp(paper, lineInk, QuarterTickBlend),
        AddButtonDisabled = Color(0xFF39322A),
        Delete = Color(0xFFC9756A),
        Scrim = Color(0xAA0B0906),
        LightBackground = false
    )
}

private val NightPalette = nightPalette(
    paper = Color(PaperBackgroundDarkArgb),
    sheet = Color(0xFF221C15),
    lineInk = Color(0xFF6F5B3E),
    timeText = Color(0xFFC6BBA8)
)

private val MiddayPalette = dayPalette(
    paper = Color(0xFFF6F2EC),
    sheet = Color(0xFFFFF9F1),
    lineInk = Color(0xFF574A38),
    timeText = Color(0xFF3F3932)
)

// minute = when this stop is fully reached. ramp = fade from the previous stop
// across the segment; otherwise the previous palette holds and the theme steps
// here in one minute. Polarity may only change on a step, never on a ramp.
private class DaylightStop(val minute: Int, val ramp: Boolean, val palette: PlannerPalette)

private val DaylightStops = listOf(
    DaylightStop(minute = 0, ramp = false, palette = NightPalette),
    DaylightStop(minute = 5 * 60, ramp = false, palette = NightPalette),
    // First light: the night sky warms before sunrise.
    DaylightStop(
        minute = 6 * 60 + 45,
        ramp = true,
        palette = nightPalette(
            paper = Color(0xFF1C1710),
            sheet = Color(0xFF291F15),
            lineInk = Color(0xFF7D6440),
            timeText = Color(0xFFCDBFA4)
        )
    ),
    // Sunrise: polarity flips to warm morning light.
    DaylightStop(
        minute = 7 * 60,
        ramp = false,
        palette = dayPalette(
            paper = Color(0xFFF3E6D0),
            sheet = Color(0xFFFCF1DE),
            lineInk = Color(0xFF6E5730),
            timeText = Color(0xFF4A3B24)
        )
    ),
    DaylightStop(
        minute = 9 * 60 + 30,
        ramp = true,
        palette = dayPalette(
            paper = Color(0xFFF5EDDE),
            sheet = Color(0xFFFEF6E8),
            lineInk = Color(0xFF625234),
            timeText = Color(0xFF443A28)
        )
    ),
    DaylightStop(minute = 13 * 60, ramp = true, palette = MiddayPalette),
    DaylightStop(
        minute = 16 * 60 + 30,
        ramp = true,
        palette = dayPalette(
            paper = Color(0xFFF6EEDD),
            sheet = Color(0xFFFEF5E5),
            lineInk = Color(0xFF64522F),
            timeText = Color(0xFF463B26)
        )
    ),
    // Golden hour: the warmest light of the day.
    DaylightStop(
        minute = 19 * 60 + 30,
        ramp = true,
        palette = dayPalette(
            paper = Color(0xFFF1E2C8),
            sheet = Color(0xFFFAEDD6),
            lineInk = Color(0xFF6E5426),
            timeText = Color(0xFF4B3B1F)
        )
    ),
    // Dusk: polarity flips back to a warm dark evening.
    DaylightStop(
        minute = 20 * 60,
        ramp = false,
        palette = nightPalette(
            paper = Color(0xFF1F1810),
            sheet = Color(0xFF2D2214),
            lineInk = Color(0xFF876A3E),
            timeText = Color(0xFFD2C2A2)
        )
    ),
    DaylightStop(minute = 22 * 60 + 30, ramp = true, palette = NightPalette)
)

// The palette changes at most once per step; only palette readers recompose.
private const val PaletteStepMinutes = 5

internal fun paletteForMinute(minuteOfDay: Int): PlannerPalette {
    val minute = minuteOfDay.coerceIn(0, TimeSnapper.MinutesPerDay - 1)
    val index = DaylightStops.indexOfLast { it.minute <= minute }
    val current = DaylightStops[index]
    val next = DaylightStops.getOrNull(index + 1)
    if (next == null || !next.ramp) return current.palette
    val fraction = (minute - current.minute).toFloat() / (next.minute - current.minute)
    return lerpPalette(current.palette, next.palette, fraction)
}

internal fun displayedPaletteForMinute(minuteOfDay: Int): PlannerPalette {
    val minute = minuteOfDay.coerceIn(0, TimeSnapper.MinutesPerDay - 1)
    return paletteForMinute(minute - minute % PaletteStepMinutes)
}

private fun lerpPalette(from: PlannerPalette, to: PlannerPalette, fraction: Float): PlannerPalette {
    return PlannerPalette(
        Paper = lerp(from.Paper, to.Paper, fraction),
        Sheet = lerp(from.Sheet, to.Sheet, fraction),
        // Ramps never cross an ink-polarity boundary, so these values are
        // identical at both ends and need no colour-space interpolation.
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

private val LocalPlannerColours = compositionLocalOf { MiddayPalette }
// The minute as a state, not a value: only the grid and the current-time indicator read
// it, so a tick reaches them and nothing between them and the theme.
internal val LocalCurrentMinuteOfDay = staticCompositionLocalOf<IntState> {
    mutableIntStateOf(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis()))
}
internal val LocalCurrentDate = compositionLocalOf<LocalDate> { TimeSnapper.dateOf(TimeSnapper.localNowMillis()) }

/** Composition-aware colours for the current time of day. */
internal val PlannerColours: PlannerPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalPlannerColours.current

private fun millisUntilNextMinute(localMillis: Long): Long =
    (TimeSnapper.MillisPerMinute - Math.floorMod(localMillis, TimeSnapper.MillisPerMinute)).coerceAtLeast(250L)

/**
 * Single owner of the system-bar style. Sheets must not style the bars
 * themselves: routing the scrim dim through here keeps theme changes and an
 * open sheet from fighting over the bars.
 */
@Composable
internal fun PlannerSystemBarsEffect(dimmed: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity
    val palette = PlannerColours
    DisposableEffect(activity, dimmed, palette) {
        activity?.applyPlannerSystemBars(palette, dimmed)
        onDispose { }
    }
}

internal fun ComponentActivity.applyPlannerSystemBars(palette: PlannerPalette, dimmed: Boolean = false) {
    val paper = palette.Paper.toArgb()
    // Paper, dim and polarity fix every colour below. Re-applying an unchanged style would
    // still update the window, which happens at launch when composition repeats what
    // onCreate set, so each distinct style is applied once.
    val style = (paper.toLong() shl 2) or (if (dimmed) 2L else 0L) or (if (palette.LightBackground) 1L else 0L)
    val decor = window.decorView
    val applied = decor.tag as? Long
    if (applied == style) return
    decor.tag = style
    // The window background is the paper, so the planner paints nothing beneath its
    // content and the screen is filled once per frame instead of twice.
    if (applied == null || applied shr 2 != style shr 2) window.setBackgroundDrawable(paper.toDrawable())
    val background = if (dimmed) palette.Scrim.compositeOver(palette.Paper) else palette.Paper
    val darkIcons = palette.LightBackground && !dimmed
    // The Compose header owns the tint behind the status icons. An opaque
    // scrim here also creates an opaque ColorProtection on Android 15+.
    enableEdgeToEdge(
        statusBarStyle = if (darkIcons) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        },
        navigationBarStyle = if (darkIcons) {
            SystemBarStyle.light(background.toArgb(), PaperBackgroundDarkArgb)
        } else {
            SystemBarStyle.dark(background.toArgb())
        }
    )
}

internal val DaytileFontFamily = FontFamily.SansSerif

// headlineMedium and bodySmall are the timeline heading's title and date line; no
// Material component used here reads either slot.
private val plannerTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )
)

// Only the slots the text field, text buttons and selection handles read; every
// surface and container colour is drawn from the palette directly.
internal fun colourSchemeFor(palette: PlannerPalette) = if (palette.LightBackground) {
    lightColorScheme(primary = palette.PrimaryText, onSurface = palette.PrimaryText, error = palette.Delete)
} else {
    darkColorScheme(primary = palette.PrimaryText, onSurface = palette.PrimaryText, error = palette.Delete)
}

@Composable
internal fun PlannerTheme(content: @Composable () -> Unit) {
    val initialNow = remember { TimeSnapper.localNowMillis() }
    val currentMinute = remember {
        mutableIntStateOf(TimeSnapper.minuteOfDay(initialNow))
    }
    var currentDate by remember { mutableStateOf(TimeSnapper.dateOf(initialNow)) }
    // The clock pauses while the app is not visible and refreshes immediately
    // on return, so backgrounding never wakes the process once a minute.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = TimeSnapper.localNowMillis()
                currentMinute.intValue = TimeSnapper.minuteOfDay(now)
                currentDate = TimeSnapper.dateOf(now)
                delay(millisUntilNextMinute(now))
            }
        }
    }

    // Recomposes only when the step changes, not on every tick.
    val paletteStep by remember { derivedStateOf { currentMinute.intValue / PaletteStepMinutes } }
    val palette = remember(paletteStep) { displayedPaletteForMinute(paletteStep * PaletteStepMinutes) }
    val colourScheme = remember(palette) { colourSchemeFor(palette) }
    // The date is provided outside MaterialTheme, so midnight skips the theme and reaches
    // only the composables that read the date.
    CompositionLocalProvider(
        LocalCurrentMinuteOfDay provides currentMinute,
        LocalCurrentDate provides currentDate
    ) {
        MaterialTheme(colorScheme = colourScheme, typography = plannerTypography) {
            CompositionLocalProvider(
                LocalPlannerColours provides palette,
                LocalContentColor provides palette.PrimaryText,
                content = content
            )
        }
    }
}
