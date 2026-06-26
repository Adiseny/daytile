package com.privateplanner.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.privateplanner.domain.TimeSnapper
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

// Window-background colours used for the cold-start splash and the system bars.
const val PaperBackgroundArgb: Int = 0xFFF6F2EC.toInt()
const val PaperBackgroundDarkArgb: Int = 0xFF15120D.toInt()

data class PlannerPalette(
    val Paper: Color,
    val Sheet: Color,
    val PrimaryText: Color,
    val MutedText: Color,
    val TimeText: Color,
    val HourLine: Color,
    val HalfHourLine: Color,
    val QuarterTick: Color,
    val AddButton: Color,
    val AddButtonDisabled: Color,
    val Delete: Color,
    val Scrim: Color,
    val GlassShade: Color
)

private val LightPalette = PlannerPalette(
    Paper = Color(0xFFF6F2EC),
    Sheet = Color(0xFFFFF9F1),
    PrimaryText = Color(0xFF1A1814),
    MutedText = Color(0xFF6F675F),
    TimeText = Color(0xFF3F3932),
    HourLine = Color(0xFFB8AA98),
    HalfHourLine = Color(0xFFC7BAA8),
    QuarterTick = Color(0xFFD2C8B9),
    AddButton = Color(0xFF1A1814),
    AddButtonDisabled = Color(0xFFE0D8CD),
    Delete = Color(0xFF9B4F45),
    Scrim = Color(0x661A1814),
    GlassShade = Color(0xFF1A1814)
)

private val DarkPalette = PlannerPalette(
    Paper = Color(0xFF15120D),
    Sheet = Color(0xFF221C15),
    PrimaryText = Color(0xFFEFE7DA),
    MutedText = Color(0xFF9C9384),
    TimeText = Color(0xFFC6BBA8),
    HourLine = Color(0xFF39322A),
    HalfHourLine = Color(0xFF322B23),
    QuarterTick = Color(0xFF2B251E),
    AddButton = Color(0xFFEFE7DA),
    AddButtonDisabled = Color(0xFF39322A),
    Delete = Color(0xFFC9756A),
    Scrim = Color(0xAA0B0906),
    GlassShade = Color(0xFF050403)
)

private val LocalPlannerColors = staticCompositionLocalOf { LightPalette }
internal val LocalCurrentMinuteOfDay = staticCompositionLocalOf {
    TimeSnapper.minuteOfDay(LocalTime.now())
}

/** Composition-aware palette accessor; resolves to the current day/night + time-of-day tint. */
val PlannerColors: PlannerPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalPlannerColors.current

private val DawnWarmth = Color(0xFFFFC980)
private val DayPaper = Color(0xFFF7F1E7)
private val DaySheet = Color(0xFFFFFAF2)
private val NightPaper = Color(0xFF15120D)
private val NightSheet = Color(0xFF221C15)
private const val HorizonWarmth = 0.12f
private const val LightInkThreshold = 0.56f

// 0 at midnight, 1 at midday. The circular curve gives a natural dawn/day/dusk/night cycle.
private fun daylightFactor(minutesOfDay: Int): Float {
    val t = minutesOfDay.coerceIn(0, TimeSnapper.MinutesPerDay) / TimeSnapper.MinutesPerDay.toFloat()
    return ((1f - cos(t * 2f * PI.toFloat())) / 2f).coerceIn(0f, 1f)
}

// Peaks around sunrise and sunset, adding a small warm cast without weakening text contrast.
private fun horizonFactor(daylight: Float): Float {
    return (1f - abs(daylight - 0.5f) / 0.5f).coerceIn(0f, 1f)
}

private fun paletteForTime(minutesOfDay: Int): PlannerPalette {
    val daylight = daylightFactor(minutesOfDay)
    val horizon = horizonFactor(daylight)
    val readable = if (daylight >= LightInkThreshold) LightPalette else DarkPalette
    val paper = lerp(
        lerp(NightPaper, DayPaper, daylight),
        DawnWarmth,
        horizon * HorizonWarmth
    )
    val sheet = lerp(
        lerp(NightSheet, DaySheet, daylight),
        DawnWarmth,
        horizon * HorizonWarmth * 0.62f
    )

    return readable.copy(
        Paper = paper,
        Sheet = sheet,
        GlassShade = if (daylight >= LightInkThreshold) LightPalette.GlassShade else DarkPalette.GlassShade
    )
}

@Composable
private fun rememberCurrentMinuteOfDay(): Int {
    var minute by remember { mutableIntStateOf(TimeSnapper.minuteOfDay(LocalTime.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            minute = TimeSnapper.minuteOfDay(LocalTime.now())
            delay(millisUntilNextMinute())
        }
    }
    return minute
}

private fun millisUntilNextMinute(): Long {
    val now = LocalTime.now()
    val millis = (60 - now.second) * 1_000L - now.nano / 1_000_000L
    return millis.coerceAtLeast(250L)
}

@Composable
private fun TimeAwareSystemBars(
    palette: PlannerPalette,
    lightBackground: Boolean
) {
    val view = LocalView.current
    val scrim = palette.Paper.toArgb()
    LaunchedEffect(view, scrim, lightBackground) {
        val style = if (lightBackground) {
            SystemBarStyle.light(scrim, PaperBackgroundDarkArgb)
        } else {
            SystemBarStyle.dark(scrim)
        }
        view.context.findComponentActivity()?.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style
        )
    }
}

internal tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

val DaytileFontFamily = FontFamily.SansSerif

private val plannerTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 32.sp
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
    labelLarge = TextStyle(
        fontFamily = DaytileFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )
)

private fun lightSchemeFrom(c: PlannerPalette) = lightColorScheme(
    primary = c.PrimaryText,
    onPrimary = c.Sheet,
    background = c.Paper,
    onBackground = c.PrimaryText,
    surface = c.Sheet,
    onSurface = c.PrimaryText,
    error = c.Delete
)

private fun darkSchemeFrom(c: PlannerPalette) = darkColorScheme(
    primary = c.PrimaryText,
    onPrimary = c.Paper,
    background = c.Paper,
    onBackground = c.PrimaryText,
    surface = c.Sheet,
    onSurface = c.PrimaryText,
    error = c.Delete
)

@Composable
fun PlannerTheme(content: @Composable () -> Unit) {
    val currentMinute = rememberCurrentMinuteOfDay()
    val daylight = daylightFactor(currentMinute)
    val palette = paletteForTime(currentMinute)
    val lightBackground = daylight >= LightInkThreshold
    val colorScheme = if (lightBackground) lightSchemeFrom(palette) else darkSchemeFrom(palette)
    TimeAwareSystemBars(palette, lightBackground)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = plannerTypography
    ) {
        CompositionLocalProvider(
            LocalCurrentMinuteOfDay provides currentMinute,
            LocalPlannerColors provides palette,
            LocalContentColor provides palette.PrimaryText
        ) {
            content()
        }
    }
}
