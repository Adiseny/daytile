package com.privateplanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.ceil

// A text style in one number: its size and line height in sp and its weight in hundreds, a
// byte each. HeadlineMedium and BodySmall are the timeline heading's title and date line,
// LabelLarge a button's label, MessageText a snackbar's and ChevronText the date sheet's arrows.
internal const val HeadlineMedium = 22 shl 16 or (27 shl 8) or 6
internal const val TitleMedium = 18 shl 16 or (24 shl 8) or 6
internal const val BodyMedium = 14 shl 16 or (18 shl 8) or 4
internal const val BodySmall = 12 shl 16 or (15 shl 8) or 4
internal const val LabelLarge = 15 shl 16 or (20 shl 8) or 6
internal const val MessageText = 14 shl 16 or (18 shl 8) or 6
internal const val ChevronText = 28 shl 16 or (22 shl 8) or 4

internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

internal fun Context.px(value: Float): Int = Math.round(value * resources.displayMetrics.density)

internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

private val Typefaces = arrayOfNulls<Typeface>(10)

// The system sans serif at a weight; before Android 9 only regular and bold exist.
internal fun typeface(weight: Int): Typeface = Typefaces[weight / 100] ?: (
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
    } else {
        Typeface.create("sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
    ).also { Typefaces[weight / 100] = it }

// Its colour is set by whoever draws with it.
internal fun Context.textPaint(size: Float, weight: Int): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.density = resources.displayMetrics.density
        it.textSize = sp(size)
        it.typeface = typeface(weight)
        it.hinting = Paint.HINTING_ON
    }

internal fun Context.textPaint(style: Int): TextPaint = textPaint((style ushr 16).toFloat(), (style and 0xFF) * 100)

// Every line exactly the line height tall, the text centred in it.
private class CentredLineHeight(private val lineHeight: Float) : LineHeightSpan {
    private var ascent = Int.MIN_VALUE
    private var descent = 0
    var above = 0
    var below = 0

    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, metrics: Paint.FontMetricsInt
    ) {
        val natural = metrics.descent - metrics.ascent
        if (natural <= 0) return
        if (ascent == Int.MIN_VALUE) {
            val target = ceil(this.lineHeight).toInt()
            descent = metrics.descent + ceil((target - natural) * 0.5f).toInt()
            ascent = descent - target
            // A line shorter than the font would cut its glyphs, so the block grows by the
            // difference instead.
            above = (ascent - metrics.ascent).coerceAtLeast(0)
            below = if (metrics.descent > descent) maxOf(above, metrics.descent - descent) else 0
        }
        metrics.ascent = ascent
        metrics.descent = descent
    }
}

// Laid-out text and where it draws within its own box.
internal class TextBlock(private val layout: StaticLayout, private val top: Int, val width: Int, val height: Int) {
    private val clipped = layout.height + top > height

    fun draw(canvas: Canvas, x: Float, y: Float, colour: Int) {
        layout.paint.color = colour
        canvas.save()
        canvas.translate(x, y)
        if (clipped) canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}

internal const val Unbounded = 1 shl 24

// Text as wide as it needs up to maxWidth (or exactly maxWidth when fill is set), cut to
// maxLines and to the lines that fit maxHeight, with an ellipsis where asked.
internal fun textBlock(
    text: String,
    paint: TextPaint,
    lineHeight: Float,
    maxWidth: Int,
    maxLines: Int = Int.MAX_VALUE,
    ellipsis: Boolean = false,
    maxHeight: Int = Unbounded,
    fill: Boolean = false,
    centred: Boolean = false,
    // A word too long for its line is divided at a syllable, with a hyphen.
    hyphenate: Boolean = false
): TextBlock {
    val span = if (lineHeight > 0f && text.isNotEmpty()) CentredLineHeight(lineHeight) else null
    val content: CharSequence = if (span == null) text else SpannableString(text).apply {
        setSpan(span, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
    val available = maxWidth.coerceAtLeast(0)
    val width = if (fill) available else minOf(ceil(Layout.getDesiredWidth(content, paint)).toInt(), available)
    fun build(lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(lines)
            .setEllipsize(if (ellipsis) TextUtils.TruncateAt.END else null)
            // BREAK_STRATEGY_SIMPLE is the platform builder's default.
            .setHyphenationFrequency(if (hyphenate) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUseLineSpacingFromFallbacks(true) }
            .build()
    var layout = build(maxLines)
    if (ellipsis && maxLines > 1 && layout.height > maxHeight) {
        var fitting = 0
        while (fitting < layout.lineCount && layout.getLineBottom(fitting) <= maxHeight) fitting++
        if (fitting != maxLines) layout = build(fitting.coerceAtLeast(1))
    }
    val lines = minOf(layout.lineCount, maxLines)
    val body = if (lines < layout.lineCount) layout.getLineBottom(lines - 1) else layout.height
    val above = span?.above ?: 0
    return TextBlock(layout, above, width, minOf(body + above + (span?.below ?: 0), maxHeight))
}

// Text in one style. It is as large as its text unless the parent says otherwise, and
// places the text within any extra room by its gravity.
internal class Label(
    context: Context,
    style: Int,
    colour: Int,
    private val maxLines: Int = Int.MAX_VALUE,
    private val ellipsis: Boolean = false,
    private val gravity: Int = Gravity.TOP or Gravity.START,
    private val centred: Boolean = false,
    // Whether every line is the style's line height tall, the text centred in it, or the
    // text keeps its font's own height.
    lineBox: Boolean = false
) : View(context) {
    private val paint = context.textPaint(style)
    private val lineHeight = if (lineBox) context.sp((style ushr 8 and 0xFF).toFloat()) else 0f
    private var block: TextBlock? = null
    private var blockWidth = -1

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            block = null
            requestLayout()
            invalidate()
        }

    var colour: Int = colour
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun blockFor(width: Int, fill: Boolean): TextBlock {
        val key = if (fill) width else -width - 1
        return block?.takeIf { blockWidth == key } ?: textBlock(
            text, paint, lineHeight, width, maxLines, ellipsis, fill = fill, centred = centred
        ).also {
            block = it
            blockWidth = key
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val horizontal = paddingLeft + paddingRight
        val exact = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
        val available = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) Unbounded
        else MeasureSpec.getSize(widthSpec) - horizontal
        val laidOut = blockFor(available, fill = exact && centred)
        setMeasuredDimension(
            if (exact) MeasureSpec.getSize(widthSpec) else maxOf(laidOut.width + horizontal, suggestedMinimumWidth),
            resolveSize(maxOf(laidOut.height + paddingTop + paddingBottom, suggestedMinimumHeight), heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val laidOut = block ?: return
        val spareWidth = width - paddingLeft - paddingRight - laidOut.width
        val spareHeight = height - paddingTop - paddingBottom - laidOut.height
        val x = if (gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL) Math.round(spareWidth / 2f) else 0
        val y = if (gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL) Math.round(spareHeight / 2f) else 0
        laidOut.draw(canvas, (paddingLeft + x).toFloat(), (paddingTop + y).toFloat(), colour)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.TextView"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = text
    }
}
