package com.privateplanner.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// The little of Material 3 the planner used, without the library: text that inherits a
// style and colour, the ripple, and the pill button. Every value is Material 1.4's, so
// nothing on screen changes.

// Material's base text style: no font padding, and extra line height split evenly above
// and below the text.
@Suppress("DEPRECATION")
private val BaseTextStyle = TextStyle.Default.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

// What unstyled text reads, as MaterialTheme provided it.
internal val BodyTextStyle = BaseTextStyle.merge(PlannerType.bodyLarge)

internal val LocalContentColor = compositionLocalOf { Color.Black }
internal val LocalTextStyle = compositionLocalOf(structuralEqualityPolicy()) { BaseTextStyle }

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(
            color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight
        ),
        overflow = overflow,
        maxLines = maxLines
    )
}

// Material's state-layer opacities.
private val PlannerRippleAlpha = RippleAlpha(
    draggedAlpha = 0.16f,
    focusedAlpha = 0.1f,
    hoveredAlpha = 0.08f,
    pressedAlpha = 0.1f
)

// Material's ripple, drawn by material-ripple as before, in the content colour where it is
// attached.
internal object PlannerRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PlannerRippleNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x52_49_50
}

private class PlannerRippleNode(interactionSource: InteractionSource) :
    DelegatingNode(), CompositionLocalConsumerModifierNode {
    init {
        delegate(
            createRippleModifierNode(
                interactionSource = interactionSource,
                bounded = true,
                radius = Dp.Unspecified,
                color = { currentValueOf(LocalContentColor) },
                rippleAlpha = { PlannerRippleAlpha }
            )
        )
    }
}

// Material's Button and TextButton: a pill at least 58 by 40 inside a slot at least 48dp
// each way, its label in labelLarge and the content colour, which the ripple also takes.
@Composable
internal fun PlannerButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val labelStyle = LocalTextStyle.current.merge(PlannerType.labelLarge)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .minimumTouchSize()
                .background(containerColor, CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = null,
                    indication = PlannerRipple,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                ),
            propagateMinConstraints = true
        ) {
            CompositionLocalProvider(LocalTextStyle provides labelStyle) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 58.dp, minHeight = 40.dp)
                        .padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}

internal val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
internal val TextButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

// Grows a smaller control to 48dp each way and centres it, leaving what it draws alone.
private fun Modifier.minimumTouchSize() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val minimum = 48.dp.roundToPx()
    val width = maxOf(placeable.width, minimum)
    val height = maxOf(placeable.height, minimum)
    layout(width, height) {
        placeable.place(((width - placeable.width) / 2f).roundToInt(), ((height - placeable.height) / 2f).roundToInt())
    }
}
