package com.privateplanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.StateSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.sign

internal fun roundedRect(colour: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(colour)
    cornerRadius = radius
}

// The platform's ripple answers every change of state, a window gaining focus included,
// with a background animation that has nothing to show unless it is focused or hovered, and
// every step of that animation redraws its view: eight frames after each launch for the
// date heading alone. This one hears only of presses, focus and hover; otherwise it stays
// in the state it was made in, so an idle ripple costs no frames.
private class QuietRipple(colour: ColorStateList, mask: Drawable) : RippleDrawable(colour, null, mask) {
    override fun setState(stateSet: IntArray): Boolean = super.setState(
        if (stateSet.any {
                it == android.R.attr.state_pressed || it == android.R.attr.state_focused || it == android.R.attr.state_hovered
            }
        ) stateSet else StateSet.WILD_CARD
    )
}

// The content colour at Material's pressed opacity. Before Android 9 the platform drew
// ripples at half strength.
internal fun rippleColour(contentColour: Int): ColorStateList =
    ColorStateList.valueOf(withAlpha(contentColour, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 0.2f else 0.1f))

// A ripple in the content colour, confined to the mask.
internal fun ripple(contentColour: Int, mask: Drawable): RippleDrawable = QuietRipple(rippleColour(contentColour), mask)

internal fun View.rippleOver(contentColour: Int, radius: Float) {
    foreground = ripple(contentColour, roundedRect(-1, radius))
}

// Children one after another, across or down. Weighted children share what the others
// leave, to the pixel; `centred` centres each on the other axis and `packed` centres the
// run as a whole, an odd pixel going after rather than before. The platform's own layouts
// round the other way, which would move text by a pixel.
internal open class Flow(
    context: Context,
    private val vertical: Boolean = false,
    private val centred: Boolean = false,
    private val packed: Boolean = false
) : ViewGroup(context) {
    class Cell(width: Int = WRAP_CONTENT, height: Int = WRAP_CONTENT, val weight: Float = 0f) : MarginLayoutParams(width, height)

    override fun generateDefaultLayoutParams(): LayoutParams = Cell()

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params is Cell

    private fun spec(size: Int, available: Int, bounded: Boolean): Int = when {
        size >= 0 -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        !bounded -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        size == LayoutParams.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(max(available, 0), MeasureSpec.EXACTLY)
        else -> MeasureSpec.makeMeasureSpec(max(available, 0), MeasureSpec.AT_MOST)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val mainSpec = if (vertical) heightSpec else widthSpec
        val crossSpec = if (vertical) widthSpec else heightSpec
        val mainPadding = if (vertical) paddingTop + paddingBottom else paddingLeft + paddingRight
        val crossPadding = if (vertical) paddingLeft + paddingRight else paddingTop + paddingBottom
        val mainBounded = MeasureSpec.getMode(mainSpec) != MeasureSpec.UNSPECIFIED
        val crossBounded = MeasureSpec.getMode(crossSpec) != MeasureSpec.UNSPECIFIED
        val mainMax = MeasureSpec.getSize(mainSpec) - mainPadding
        val crossMax = MeasureSpec.getSize(crossSpec) - crossPadding
        var fixed = 0
        var cross = 0
        var totalWeight = 0f

        // Measures the child along the main axis as told, and answers what it takes across.
        fun measure(child: View, cell: Cell, main: Int): Int {
            val crossMargins = if (vertical) cell.leftMargin + cell.rightMargin else cell.topMargin + cell.bottomMargin
            val crossSize = spec(if (vertical) cell.width else cell.height, crossMax - crossMargins, crossBounded)
            if (vertical) child.measure(crossSize, main) else child.measure(main, crossSize)
            return (if (vertical) child.measuredWidth else child.measuredHeight) + crossMargins
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val cell = child.layoutParams as Cell
            val margins = if (vertical) cell.topMargin + cell.bottomMargin else cell.leftMargin + cell.rightMargin
            fixed += margins
            if (cell.weight > 0f && mainBounded) {
                totalWeight += cell.weight
            } else {
                cross = max(cross, measure(child, cell, spec(if (vertical) cell.height else cell.width, mainMax - fixed, mainBounded)))
                fixed += if (vertical) child.measuredHeight else child.measuredWidth
            }
        }
        if (totalWeight > 0f) {
            val remaining = max(mainMax - fixed, 0)
            val unit = remaining / totalWeight
            var remainder = remaining
            for (index in 0 until childCount) {
                val cell = getChildAt(index).layoutParams as Cell
                if (getChildAt(index).visibility != GONE && cell.weight > 0f) remainder -= Math.round(unit * cell.weight)
            }
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val cell = child.layoutParams as Cell
                if (child.visibility == GONE || cell.weight <= 0f) continue
                val size = max(Math.round(unit * cell.weight) + remainder.sign, 0)
                remainder -= remainder.sign
                cross = max(cross, measure(child, cell, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)))
            }
            fixed = mainMax
        }
        val main = max(fixed + mainPadding, if (vertical) suggestedMinimumHeight else suggestedMinimumWidth)
        val crossTotal = max(cross + crossPadding, if (vertical) suggestedMinimumWidth else suggestedMinimumHeight)
        setMeasuredDimension(
            resolveSize(if (vertical) crossTotal else main, widthSpec),
            resolveSize(if (vertical) main else crossTotal, heightSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val mainSize = (if (vertical) bottom - top - paddingTop - paddingBottom else right - left - paddingLeft - paddingRight)
        val crossSize = (if (vertical) right - left - paddingLeft - paddingRight else bottom - top - paddingTop - paddingBottom)
        var position = if (vertical) paddingTop else paddingLeft
        if (packed) {
            var content = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val cell = child.layoutParams as Cell
                content += if (vertical) child.measuredHeight + cell.topMargin + cell.bottomMargin
                else child.measuredWidth + cell.leftMargin + cell.rightMargin
            }
            position += Math.round((mainSize - content) / 2f)
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val cell = child.layoutParams as Cell
            if (vertical) {
                position += cell.topMargin
                val x = paddingLeft + cell.leftMargin +
                    if (centred) Math.round((crossSize - cell.leftMargin - cell.rightMargin - child.measuredWidth) / 2f) else 0
                child.layout(x, position, x + child.measuredWidth, position + child.measuredHeight)
                position += child.measuredHeight + cell.bottomMargin
            } else {
                position += cell.leftMargin
                val y = paddingTop + cell.topMargin +
                    if (centred) Math.round((crossSize - cell.topMargin - cell.bottomMargin - child.measuredHeight) / 2f) else 0
                child.layout(position, y, position + child.measuredWidth, y + child.measuredHeight)
                position += child.measuredWidth + cell.rightMargin
            }
        }
    }
}

// Material's button: a pill at least 58 by 40 inside a slot at least 48dp each way, which
// takes the touches. `pillHeight` (dp) makes the pill that tall; zero leaves it its minimum.
internal class PlannerButton(
    context: Context,
    contentColour: Int,
    horizontalPadding: Float,
    pillHeight: Float = 0f
) : ViewGroup(context) {
    private val fill = roundedRect(0, context.dp(1000f))

    val pill = Flow(context, centred = true, packed = true).apply {
        minimumWidth = context.px(58f)
        minimumHeight = context.px(if (pillHeight > 0f) pillHeight else 40f)
        setPadding(context.px(horizontalPadding), context.px(8f), context.px(horizontalPadding), context.px(8f))
        background = fill
        foreground = ripple(contentColour, roundedRect(-1, context.dp(1000f)))
        isDuplicateParentStateEnabled = true
    }

    var container: Int = 0
        set(value) {
            if (field == value) return
            field = value
            fill.setColor(value)
        }

    init {
        isClickable = true
        // The pill's ripple shows the focus; the platform's highlight would shade the slot.
        defaultFocusHighlightEnabled = false
        addView(pill)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        pill.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) heightSpec else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val minimum = context.px(48f)
        setMeasuredDimension(max(pill.measuredWidth, minimum), resolveSize(max(pill.measuredHeight, minimum), heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = Math.round((right - left - pill.measuredWidth) / 2f)
        val y = Math.round((bottom - top - pill.measuredHeight) / 2f)
        pill.layout(x, y, x + pill.measuredWidth, y + pill.measuredHeight)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}
