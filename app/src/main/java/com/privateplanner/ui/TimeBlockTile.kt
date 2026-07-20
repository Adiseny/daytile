package com.privateplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val ActiveTileAlpha = 0.70f
private const val IdleTileAlpha = 0.86f
private const val BlackWhiteContrastSwitchLuminance = 0.17912878f

internal fun compositedTileBackground(
    background: Color,
    paper: Color,
    active: Boolean
): Color = background
    .copy(alpha = if (active) ActiveTileAlpha else IdleTileAlpha)
    .compositeOver(paper)

internal fun tileInkFor(background: Color, paper: Color, active: Boolean): Color {
    val surface = compositedTileBackground(background, paper, active)
    return if (surface.luminance() >= BlackWhiteContrastSwitchLuminance) {
        Color.Black
    } else {
        Color.White
    }
}

@Composable
internal fun TimeBlockForeground(
    background: Color,
    shape: RoundedCornerShape,
    active: Boolean,
    title: String,
    rangeText: () -> String,
    durationText: String,
    tileWidth: Dp,
    visualHeight: Dp,
    titleFollowOffset: Density.() -> Int,
    modifier: Modifier
) {
    val paper = PlannerColours.Paper
    val ink = remember(background, paper, active) { tileInkFor(background, paper, active) }
    Box(
        modifier = modifier
            .background(
                color = background.copy(alpha = if (active) ActiveTileAlpha else IdleTileAlpha),
                shape = shape
            )
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = ink.copy(alpha = 0.30f),
                shape = shape
            )
            .drawWithContent {
                drawContent()
                val handleWidth = ResizeHandleWidth.toPx()
                val handleHeight = ResizeHandleHeight.toPx()
                drawRoundRect(
                    color = ink.copy(alpha = 0.18f),
                    topLeft = Offset(
                        x = (size.width - handleWidth) / 2f,
                        y = size.height - ResizeHandleBottomPadding.toPx() - handleHeight
                    ),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = CornerRadius(handleHeight / 2f)
                )
            }
    ) {
        BlockContent(
            title = title,
            rangeText = rangeText,
            durationText = durationText,
            tileWidth = tileWidth,
            height = visualHeight,
            titleFollowOffset = titleFollowOffset,
            ink = ink
        )

    }
}

internal fun heightForMinutes(minutes: Int): Dp = HourHeight * (minutes / 60f)

internal fun centredTouchTop(top: Dp, contentHeight: Dp): Dp {
    val touchHeight = contentHeight.coerceAtLeast(MinimumTouchTarget)
    return (top - (touchHeight - contentHeight) / 2)
        .coerceIn(0.dp, (DayHeight - touchHeight).coerceAtLeast(0.dp))
}

internal fun Density.titleFollowOffsetPx(
    scrollPx: Int,
    blockTop: Dp,
    visualHeight: Dp,
    headerBottomPx: Int
): Int {
    if (headerBottomPx <= 0) return 0
    val desiredTitleTop = scrollPx + headerBottomPx + 6.dp.toPx() -
        TimelineTopClearance.toPx() - blockTop.toPx()
    val normalTitleTop = 8.dp.toPx()
    val maxOffset = (visualHeight - 56.dp).coerceAtLeast(0.dp).toPx()
    return (desiredTitleTop - normalTitleTop).coerceIn(0f, maxOffset).roundToInt()
}

internal fun Modifier.dragTranslationLayer(
    active: Boolean,
    dragOffsetPx: FloatState
): Modifier {
    if (!active) return this
    return graphicsLayer {
        translationY = dragOffsetPx.floatValue
    }
}

@Composable
private fun BlockContent(
    title: String,
    rangeText: () -> String,
    durationText: String,
    tileWidth: Dp,
    height: Dp,
    titleFollowOffset: Density.() -> Int,
    ink: Color
) {
    val compact = height < 48.dp
    val durationFontSizeValue = when {
        height < 32.dp -> 11f
        height < 64.dp -> 12f
        height < 128.dp -> 13f
        else -> 14f
    }
    val fontScale = LocalDensity.current.fontScale
    val durationReserveValue = remember(
        tileWidth,
        durationText,
        compact,
        durationFontSizeValue,
        fontScale
    ) {
        durationReserveDp(
            tileWidthDp = tileWidth.value,
            durationText = durationText,
            compact = compact,
            durationFontSizeSp = durationFontSizeValue,
            fontScale = fontScale
        )
    }
    val showDuration = durationReserveValue > 0f
    val durationReserve = durationReserveValue.dp
    val endPadding = if (showDuration) durationReserve else 8.dp

    if (showDuration) {
        Box(modifier = Modifier.fillMaxSize()) {
            BlockPrimaryContent(title, rangeText, height, endPadding, titleFollowOffset, ink)
            DurationLabel(
                text = durationText,
                fontSize = durationFontSizeValue.sp,
                lineHeight = (durationFontSizeValue + 2f).sp,
                endPadding = if (compact) 5.dp else 8.dp,
                fontWeight = if (height >= 64.dp) FontWeight.Bold else FontWeight.SemiBold,
                ink = ink,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(durationReserve)
            )
        }
    } else {
        BlockPrimaryContent(title, rangeText, height, endPadding, titleFollowOffset, ink)
    }
}

@Composable
private fun BlockPrimaryContent(
    title: String,
    rangeText: () -> String,
    height: Dp,
    endPadding: Dp,
    titleFollowOffset: Density.() -> Int,
    ink: Color
) {
    when {
        height < 14.dp -> Unit
        height < 32.dp -> BlockOneLineContent(
            title = title,
            titleFontSize = 10.sp,
            titleLineHeight = 11.sp,
            ink = ink,
            modifier = Modifier.padding(start = 9.dp, end = endPadding)
        )
        height < 48.dp -> BlockOneLineContent(
            title = title,
            titleFontSize = 11.sp,
            titleLineHeight = 13.sp,
            ink = ink,
            modifier = Modifier.padding(start = 10.dp, end = endPadding)
        )
        else -> BlockTwoLineContent(
            title = title,
            rangeText = rangeText,
            titleFontSize = if (height < 64.dp) 12.sp else 14.sp,
            titleLineHeight = if (height < 64.dp) 14.sp else 18.sp,
            titleMaxLines = if (height < 80.dp) 1 else 2,
            metaFontSize = if (height < 64.dp) 10.sp else 12.sp,
            metaLineHeight = if (height < 64.dp) 12.sp else 16.sp,
            ink = ink,
            modifier = Modifier.padding(
                start = 12.dp,
                end = endPadding,
                top = if (height < 64.dp) 5.dp else 8.dp,
                bottom = if (height < 64.dp) 4.dp else 8.dp
            ),
            titleFollowOffset = titleFollowOffset
        )
    }
}

@Composable
private fun BlockOneLineContent(
    title: String,
    titleFontSize: TextUnit,
    titleLineHeight: TextUnit,
    ink: Color,
    modifier: Modifier
) {
    Text(
        text = title,
        color = ink,
        fontFamily = DaytileFontFamily,
        fontSize = titleFontSize,
        lineHeight = titleLineHeight,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxSize()
            .wrapContentHeight(Alignment.CenterVertically)
    )
}

@Composable
private fun BlockTwoLineContent(
    title: String,
    rangeText: () -> String,
    titleFontSize: TextUnit,
    titleLineHeight: TextUnit,
    titleMaxLines: Int,
    metaFontSize: TextUnit,
    metaLineHeight: TextUnit,
    ink: Color,
    modifier: Modifier,
    titleFollowOffset: Density.() -> Int
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, titleFollowOffset()) }
    ) {
        Text(
            text = title,
            color = ink,
            fontFamily = DaytileFontFamily,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.SemiBold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rangeText(),
            color = ink,
            fontFamily = DaytileFontFamily,
            fontSize = metaFontSize,
            lineHeight = metaLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DurationLabel(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    endPadding: Dp,
    fontWeight: FontWeight,
    ink: Color,
    modifier: Modifier
) {
    Text(
        text = text,
        color = ink,
        fontFamily = DaytileFontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.padding(end = endPadding)
    )
}
