package com.privateplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.TimeBlockForeground(
    background: Color,
    blockCornerRadius: Dp,
    compact: Boolean,
    movingGlass: Boolean,
    selected: Boolean,
    title: String,
    rangeText: String,
    durationText: String,
    tileWidth: Dp,
    visualHeight: Dp,
    titleFollowOffset: Density.() -> Int
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .liquidGlassTileSurface(
                tint = background,
                shade = PlannerColors.GlassShade,
                cornerRadius = blockCornerRadius,
                compact = compact,
                lifted = movingGlass,
                selectionColor = if (selected) {
                    PlannerColors.PrimaryText.copy(alpha = 0.52f)
                } else {
                    Color.Transparent
                }
            )
    ) {
        BlockContent(
            title = title,
            rangeText = rangeText,
            durationText = durationText,
            tileWidth = tileWidth,
            height = visualHeight,
            titleFollowOffset = titleFollowOffset
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ResizeHandleBottomPadding)
                .width(ResizeHandleWidth)
                .height(ResizeHandleHeight)
                .clip(RoundedCornerShape(ResizeHandleHeight))
                .background(PlannerColors.PrimaryText.copy(alpha = 0.18f))
        )
    }
}

internal fun heightForMinutes(minutes: Int): Dp = HourHeight * (minutes / 60f)

internal fun centeredTouchTop(top: Dp, contentHeight: Dp): Dp {
    val touchHeight = contentHeight.coerceAtLeast(MinimumTouchTarget)
    return (top - (touchHeight - contentHeight) / 2)
        .coerceIn(0.dp, (DayHeight - touchHeight).coerceAtLeast(0.dp))
}

internal fun Density.titleFollowOffsetPx(
    scrollPx: Int,
    blockTop: Dp,
    visualHeight: Dp
): Int {
    val desiredTitleTop = scrollPx + (TimelineHeaderHeight + 6.dp - TimelineTopClearance - blockTop).toPx()
    val normalTitleTop = 8.dp.toPx()
    val maxOffset = (visualHeight - 56.dp).coerceAtLeast(0.dp).toPx()
    return (desiredTitleTop - normalTitleTop).coerceIn(0f, maxOffset).roundToInt()
}

internal fun Modifier.snappedMoveLayer(offset: Dp): Modifier {
    if (offset == 0.dp) return this
    return graphicsLayer {
        translationY = offset.toPx()
    }
}

internal fun Modifier.liftedGlassLayer(
    shape: RoundedCornerShape,
    lifted: Boolean,
    offset: Dp
): Modifier {
    return graphicsLayer {
        translationY = offset.toPx()
        shadowElevation = if (lifted) 0f else 4.dp.toPx()
        this.shape = shape
        clip = false
        ambientShadowColor = Color.Black.copy(alpha = if (lifted) 0f else 0.055f)
        spotShadowColor = Color.Black.copy(alpha = if (lifted) 0f else 0.040f)
    }
}

@Composable
private fun Modifier.liquidGlassTileSurface(
    tint: Color,
    shade: Color,
    cornerRadius: Dp,
    compact: Boolean,
    lifted: Boolean = false,
    selectionColor: Color = Color.Transparent
): Modifier {
    return drawWithCache {
        val corner = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        val warmWhite = Color(0xFFFFFBF4)
        val glassTint = if (lifted) lerp(tint, warmWhite, 0.55f) else lerp(tint, warmWhite, 0.45f)
        val bodyAlpha = when {
            lifted -> 0.60f
            compact -> 0.90f
            else -> 0.94f
        }
        val borderColor = lerp(tint, shade, 0.32f)
        val borderWidthPx = (if (lifted) 2.dp else 1.dp).toPx()
        val selectionFill = selectionColor.copy(alpha = selectionColor.alpha * 0.16f)
        onDrawWithContent {
            drawRoundRect(color = glassTint.copy(alpha = bodyAlpha), cornerRadius = corner)
            inset(borderWidthPx / 2f) {
                drawRoundRect(
                    color = borderColor.copy(alpha = if (lifted) 0.85f else 0.60f),
                    cornerRadius = CornerRadius(
                        (corner.x - borderWidthPx / 2f).coerceAtLeast(0f),
                        (corner.y - borderWidthPx / 2f).coerceAtLeast(0f)
                    ),
                    style = Stroke(borderWidthPx)
                )
            }
            if (selectionColor.alpha > 0f) {
                drawRoundRect(
                    color = selectionFill,
                    cornerRadius = corner
                )
            }
            drawContent()
            if (selectionColor.alpha > 0f) {
                drawRoundRect(
                    color = selectionColor,
                    cornerRadius = corner,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun BlockContent(
    title: String,
    rangeText: String,
    durationText: String,
    tileWidth: Dp,
    height: Dp,
    titleFollowOffset: Density.() -> Int = { 0 }
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val compact = height < 48.dp
        val durationFontSizeValue = when {
            height < 32.dp -> 11f
            height < 64.dp -> 12f
            height < 128.dp -> 13f
            else -> 14f
        }
        val durationFontSize = durationFontSizeValue.sp
        val durationLineHeight = (durationFontSizeValue + 2f).sp
        val durationEndPadding = if (compact) 5.dp else 8.dp
        val durationTextAlpha = if (compact) 0.80f else 0.86f
        val durationFontWeight = if (height >= 64.dp) FontWeight.Bold else FontWeight.SemiBold
        val durationDecision = remember(tileWidth, durationText, compact, durationFontSizeValue) {
            TilePolicy.durationDisplayDecision(
                tileWidthDp = tileWidth.value,
                durationText = durationText,
                compact = compact,
                durationFontSizeSp = durationFontSizeValue
            )
        }
        val durationReserve = if (durationDecision.show) {
            durationDecision.reserveDp.dp
        } else {
            0.dp
        }
        val endPadding = if (durationDecision.show) durationReserve else 8.dp

        when {
            height < 14.dp -> Unit
            height < 32.dp -> BlockOneLineContent(
                title = title,
                titleFontSize = 10.sp,
                titleLineHeight = 11.sp,
                modifier = Modifier.padding(start = 9.dp, end = endPadding)
            )
            height < 48.dp -> BlockOneLineContent(
                title = title,
                titleFontSize = 11.sp,
                titleLineHeight = 13.sp,
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
                modifier = Modifier.padding(
                    start = 12.dp,
                    end = endPadding,
                    top = if (height < 64.dp) 5.dp else 8.dp,
                    bottom = if (height < 64.dp) 4.dp else 8.dp
                ),
                titleFollowOffset = titleFollowOffset
            )
        }

        if (durationDecision.show) {
            DurationLabel(
                text = durationText,
                fontSize = durationFontSize,
                lineHeight = durationLineHeight,
                endPadding = durationEndPadding,
                textAlpha = durationTextAlpha,
                fontWeight = durationFontWeight,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(durationReserve)
            )
        }
    }
}

@Composable
private fun BlockOneLineContent(
    title: String,
    titleFontSize: TextUnit,
    titleLineHeight: TextUnit,
    modifier: Modifier
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.fillMaxSize()
    ) {
        Text(
            text = title,
            color = PlannerColors.PrimaryText,
            fontFamily = DaytileFontFamily,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BlockTwoLineContent(
    title: String,
    rangeText: String,
    titleFontSize: TextUnit,
    titleLineHeight: TextUnit,
    titleMaxLines: Int,
    metaFontSize: TextUnit,
    metaLineHeight: TextUnit,
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
            color = PlannerColors.PrimaryText,
            fontFamily = DaytileFontFamily,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.SemiBold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rangeText,
            color = PlannerColors.PrimaryText.copy(alpha = 0.82f),
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
    textAlpha: Float,
    fontWeight: FontWeight,
    modifier: Modifier
) {
    Text(
        text = text,
        color = PlannerColors.PrimaryText.copy(alpha = textAlpha),
        fontFamily = DaytileFontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.padding(end = endPadding)
    )
}
