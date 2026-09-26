package com.privateplanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val ActiveTileAlpha = 0.70f
private const val IdleTileAlpha = 0.86f
private const val BlackWhiteContrastSwitchLuminance = 0.17912878f
private const val DurationVisibleMinWidthDp = 112f
private const val DurationTitleRemainderMinDp = 56f
private const val DurationMaxReserveFraction = 0.34f

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
    shape: Shape,
    active: Boolean,
    title: String,
    rangeText: () -> String,
    durationText: String,
    tileWidth: Dp,
    visualHeight: Dp,
    titleFollowOffset: (Density.() -> Int)?,
    modifier: Modifier
) {
    val paper = PlannerColours.Paper
    val ink = remember(background, paper, active) { tileInkFor(background, paper, active) }
    // Cached on what it draws, so a recomposition that changes none of it keeps the node's
    // draw cache instead of rebuilding it.
    val surface = remember(background, ink, shape, active) {
        Modifier.tileSurface(
            fill = background.copy(alpha = if (active) ActiveTileAlpha else IdleTileAlpha),
            ink = ink,
            shape = shape,
            borderWidth = if (active) 1.5.dp else 1.dp
        )
    }
    Box(modifier = modifier.then(surface)) {
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

// One cached draw node where background, border and a handle overlay would take three.
// It paints in their order (fill, content, resize handle, border) and with their exact
// geometry: the fill is the shape's outline, and the border is a whole-pixel stroke
// inset by half its width with correspondingly smaller corners, as Modifier.border
// draws it. Tiles are never small enough for its thin-shape fallbacks.
private fun Modifier.tileSurface(fill: Color, ink: Color, shape: Shape, borderWidth: Dp): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius ?: CornerRadius.Zero
        val stroke = min(ceil(borderWidth.toPx()), ceil(size.minDimension / 2))
        val halfStroke = stroke / 2
        val borderColour = ink.copy(alpha = 0.30f)
        val borderTopLeft = Offset(halfStroke, halfStroke)
        val borderSize = Size(size.width - stroke, size.height - stroke)
        val borderCorner = CornerRadius(max(0f, corner.x - halfStroke), max(0f, corner.y - halfStroke))
        val borderStyle = Stroke(stroke)
        val handleColour = ink.copy(alpha = 0.18f)
        val handleWidth = ResizeHandleWidth.toPx()
        val handleHeight = ResizeHandleHeight.toPx()
        val handleTopLeft = Offset(
            x = (size.width - handleWidth) / 2f,
            y = size.height - ResizeHandleBottomPadding.toPx() - handleHeight
        )
        val handleSize = Size(handleWidth, handleHeight)
        val handleCorner = CornerRadius(handleHeight / 2f)
        onDrawWithContent {
            drawOutline(outline, fill)
            drawContent()
            drawRoundRect(handleColour, handleTopLeft, handleSize, handleCorner)
            drawRoundRect(borderColour, borderTopLeft, borderSize, borderCorner, style = borderStyle)
        }
    }

internal fun durationReserveDp(
    tileWidthDp: Float,
    durationText: String,
    compact: Boolean,
    durationFontSizeSp: Float,
    fontScale: Float
): Float {
    if (tileWidthDp < DurationVisibleMinWidthDp) return 0f
    val estimatedTextWidthDp = durationText.length * durationFontSizeSp * fontScale * 0.58f
    val reserveDp = estimatedTextWidthDp + if (compact) 10f else 12f
    val maxReserveDp = tileWidthDp * DurationMaxReserveFraction
    return if (
        reserveDp <= maxReserveDp &&
        tileWidthDp - reserveDp >= DurationTitleRemainderMinDp
    ) {
        reserveDp
    } else {
        0f
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

// Emitted straight into the tile's box: a box of its own around the title and duration
// would measure and place them exactly as the tile's does.
@Composable
private fun BoxScope.BlockContent(
    title: String,
    rangeText: () -> String,
    durationText: String,
    tileWidth: Dp,
    height: Dp,
    titleFollowOffset: (Density.() -> Int)?,
    ink: Color
) {
    val compact = height < 48.dp
    val durationFontSizeValue = when {
        height < 32.dp -> 11f
        height < 64.dp -> 12f
        height < 128.dp -> 13f
        else -> 14f
    }
    // A handful of float operations: cheaper to redo than to remember.
    val durationReserveValue = durationReserveDp(
        tileWidthDp = tileWidth.value,
        durationText = durationText,
        compact = compact,
        durationFontSizeSp = durationFontSizeValue,
        fontScale = LocalDensity.current.fontScale
    )
    val showDuration = durationReserveValue > 0f
    val durationReserve = durationReserveValue.dp
    val endPadding = if (showDuration) durationReserve else 8.dp

    BlockPrimaryContent(title, rangeText, height, endPadding, titleFollowOffset, ink)
    if (showDuration) {
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
}

@Composable
private fun BlockPrimaryContent(
    title: String,
    rangeText: () -> String,
    height: Dp,
    endPadding: Dp,
    titleFollowOffset: (Density.() -> Int)?,
    ink: Color
) {
    when {
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
    titleFollowOffset: (Density.() -> Int)?
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (titleFollowOffset != null) Modifier.offset { IntOffset(0, titleFollowOffset()) } else Modifier)
    ) {
        Text(
            text = title,
            color = ink,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.SemiBold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rangeText(),
            color = ink,
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
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.padding(end = endPadding)
    )
}
