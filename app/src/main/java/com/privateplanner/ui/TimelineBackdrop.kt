package com.privateplanner.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.TimelineGlassBackdrop(
    timelineWidth: Dp,
    tileLeft: Dp,
    tileTop: Dp,
    shape: RoundedCornerShape,
    compact: Boolean
) {
    val density = LocalDensity.current
    val paper = PlannerColors.Paper
    val hourLine = PlannerColors.HourLine
    val halfHourLine = PlannerColors.HalfHourLine
    val quarterTick = PlannerColors.QuarterTick
    var tileSize by remember { mutableStateOf(IntSize.Zero) }
    val blurRadiusPx = with(density) {
        (if (compact) 2.8.dp else 4.8.dp).toPx()
    }
    val edgeDepthPx = with(density) {
        (if (compact) 13.dp else 27.dp).toPx()
    }
    val cornerRadiusPx = with(density) {
        when {
            compact -> 13.dp
            else -> 16.dp
        }.toPx()
    }
    val backdropEffect = rememberTimelineBackdropRenderEffect(
        widthPx = tileSize.width.toFloat().coerceAtLeast(1f),
        heightPx = tileSize.height.toFloat().coerceAtLeast(1f),
        blurRadiusPx = blurRadiusPx,
        edgeDepthPx = edgeDepthPx,
        cornerRadiusPx = cornerRadiusPx
    )

    val backdropAlpha = when {
        compact -> 0.18f
        else -> 0.34f
    }

    Box(
        modifier = Modifier
            .onSizeChanged { tileSize = it }
            .matchParentSize()
            .graphicsLayer {
                this.shape = shape
                clip = true
                alpha = backdropAlpha
                renderEffect = backdropEffect
            }
            .drawWithCache {
                val tileLeftPx = tileLeft.toPx().roundToInt().toFloat()
                val tileTopPx = tileTop.toPx().roundToInt().toFloat()
                val timelineWidthPx = timelineWidth.toPx()
                val hourHeightPx = HourHeight.toPx()
                val gutterPx = TimelineGutter.toPx()
                val fiveMinuteTickLengthPx = 10.dp.toPx()
                val quarterTickLengthPx = 18.dp.toPx()
                val strokePx = 1.dp.toPx()
                val fiveMinTick = quarterTick.copy(alpha = 0.72f)
                val stroke = Stroke(width = strokePx)
                val hourPath = Path()
                val halfHourPath = Path()
                val quarterPath = Path()
                val fiveMinutePath = Path()
                addTimelineGridCropPaths(
                    hourPath = hourPath,
                    halfHourPath = halfHourPath,
                    quarterPath = quarterPath,
                    fiveMinutePath = fiveMinutePath,
                    tileLeftPx = tileLeftPx,
                    tileTopPx = tileTopPx,
                    tileHeightPx = size.height,
                    timelineWidthPx = timelineWidthPx,
                    hourHeightPx = hourHeightPx,
                    gutterPx = gutterPx,
                    fiveMinuteTickLengthPx = fiveMinuteTickLengthPx,
                    quarterTickLengthPx = quarterTickLengthPx,
                    strokePx = strokePx
                )

                onDrawBehind {
                    drawRect(paper)
                    drawPath(hourPath, hourLine, style = stroke)
                    drawPath(halfHourPath, halfHourLine, style = stroke)
                    drawPath(quarterPath, quarterTick, style = stroke)
                    drawPath(fiveMinutePath, fiveMinTick, style = stroke)
                }
            }
    )
}

private fun addTimelineGridCropPaths(
    hourPath: Path,
    halfHourPath: Path,
    quarterPath: Path,
    fiveMinutePath: Path,
    tileLeftPx: Float,
    tileTopPx: Float,
    tileHeightPx: Float,
    timelineWidthPx: Float,
    hourHeightPx: Float,
    gutterPx: Float,
    fiveMinuteTickLengthPx: Float,
    quarterTickLengthPx: Float,
    strokePx: Float
) {
    val localGutterX = gutterPx - tileLeftPx
    val localTimelineEndX = timelineWidthPx - tileLeftPx
    val localFiveMinuteTickEndX = gutterPx + fiveMinuteTickLengthPx - tileLeftPx
    val localQuarterTickEndX = gutterPx + quarterTickLengthPx - tileLeftPx
    val topAbs = tileTopPx - strokePx
    val bottomAbs = tileTopPx + tileHeightPx + strokePx
    val firstHour = floor(topAbs / hourHeightPx).toInt().coerceIn(0, 24)
    val lastHour = ceil(bottomAbs / hourHeightPx).toInt().coerceIn(0, 24)

    fun Path.addVisibleLine(yAbs: Float, endX: Float) {
        if (yAbs < topAbs || yAbs > bottomAbs) return
        val y = yAbs - tileTopPx
        line(localGutterX, y, endX, y)
    }

    for (hour in firstHour..lastHour) {
        val hourYAbs = (hour * hourHeightPx).roundToInt().toFloat()
        hourPath.addVisibleLine(hourYAbs, localTimelineEndX)

        if (hour < 24) {
            for (tick in 1 until 12) {
                if (tick % 3 != 0) {
                    val tickYAbs = (hourYAbs + hourHeightPx * tick / 12f).roundToInt().toFloat()
                    fiveMinutePath.addVisibleLine(tickYAbs, localFiveMinuteTickEndX)
                }
            }
            for (quarter in 1..3) {
                val tickYAbs = (hourYAbs + hourHeightPx * quarter / 4f).roundToInt().toFloat()
                if (quarter == 2) {
                    halfHourPath.addVisibleLine(tickYAbs, localTimelineEndX)
                } else {
                    quarterPath.addVisibleLine(tickYAbs, localQuarterTickEndX)
                }
            }
        }
    }
}

@Composable
private fun rememberTimelineBackdropRenderEffect(
    widthPx: Float,
    heightPx: Float,
    blurRadiusPx: Float,
    edgeDepthPx: Float,
    cornerRadiusPx: Float
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

    val blur = remember(blurRadiusPx) {
        AndroidRenderEffect.createBlurEffect(
            blurRadiusPx,
            blurRadiusPx,
            Shader.TileMode.CLAMP
        )
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return remember(blur) {
            blur.asComposeRenderEffect()
        }
    }

    val shader = remember {
        RuntimeShader(TimelineBackdropLensAgsl)
    }
    val uniforms = remember(shader) { TimelineBackdropUniforms() }

    SideEffect {
        if (uniforms.update(widthPx, heightPx, edgeDepthPx, cornerRadiusPx)) {
            shader.setFloatUniform("size", widthPx, heightPx)
            shader.setFloatUniform("edgeDepth", edgeDepthPx)
            shader.setFloatUniform("cornerRadius", cornerRadiusPx)
        }
    }

    return remember(blur, shader) {
        AndroidRenderEffect
            .createChainEffect(
                AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"),
                blur
            )
            .asComposeRenderEffect()
    }
}

private class TimelineBackdropUniforms {
    private var widthPx = Float.NaN
    private var heightPx = Float.NaN
    private var edgeDepthPx = Float.NaN
    private var cornerRadiusPx = Float.NaN

    fun update(
        widthPx: Float,
        heightPx: Float,
        edgeDepthPx: Float,
        cornerRadiusPx: Float
    ): Boolean {
        if (
            this.widthPx == widthPx &&
            this.heightPx == heightPx &&
            this.edgeDepthPx == edgeDepthPx &&
            this.cornerRadiusPx == cornerRadiusPx
        ) {
            return false
        }
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.edgeDepthPx = edgeDepthPx
        this.cornerRadiusPx = cornerRadiusPx
        return true
    }
}

private const val TimelineBackdropLensAgsl = """
uniform shader content;
uniform float2 size;
uniform float edgeDepth;
uniform float cornerRadius;

float roundedRectSdf(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedRectNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    float2 s = sign(p);
    if (q.x > 0.0 && q.y > 0.0) {
        return normalize(max(q, 0.0) * s + float2(0.0001));
    }
    if (q.x > q.y) {
        return normalize(float2(s.x, 0.0) + float2(0.0001));
    }
    return normalize(float2(0.0, s.y) + float2(0.0001));
}

half4 main(float2 p) {
    float2 center = size * 0.5;
    float2 halfSize = size * 0.5;
    float radius = max(cornerRadius, 1.0);
    float rimWidth = max(radius * 2.05, 10.0);
    float2 local = p - center;

    float distance = roundedRectSdf(local, halfSize, radius);
    float innerDepth = max(-distance, 0.0);
    float rim = 1.0 - smoothstep(0.0, rimWidth, innerDepth);
    float roll = rim * rim * (3.0 - 2.0 * rim);
    float broadRoll = pow(clamp(roll, 0.0, 1.0), 1.34);

    float2 normal = roundedRectNormal(local, halfSize, radius);
    float2 cornerVector = max(abs(local) - (halfSize - float2(radius)), 0.0);
    float cornerAmount = smoothstep(0.0, radius * 0.86, length(cornerVector));
    float along = dot(local, float2(-normal.y, normal.x));
    float lobeNoise =
        sin(along * 0.024 + local.x * 0.006) * 0.50 +
        sin(along * 0.041 - local.y * 0.004 + 2.1) * 0.30 +
        sin((local.x - local.y) * 0.018 + 0.8) * 0.20;
    float lobe = smoothstep(0.25, 0.78, lobeNoise * 0.5 + 0.5);
    float pointBoost = mix(0.92, 1.28, lobe);
    float depth = edgeDepth * mix(0.74, 1.20, cornerAmount) * pointBoost;
    float2 tangent = float2(-normal.y, normal.x);

    float tangentBend = sin(along * 0.032 + cornerAmount * 2.0) * edgeDepth * 0.030 * broadRoll * lobe;
    float2 lensOffset = normal * broadRoll * depth + tangent * tangentBend;
    float2 samplePoint = clamp(
        p - lensOffset,
        float2(1.0),
        size - float2(1.0)
    );

    half4 c = content.eval(samplePoint);

    float lift = 0.014 + broadRoll * 0.018;
    c.rgb = mix(c.rgb, half3(1.0), half(lift));
    c.rgb *= half3(1.0 + broadRoll * 0.008);
    return c;
}
"""
