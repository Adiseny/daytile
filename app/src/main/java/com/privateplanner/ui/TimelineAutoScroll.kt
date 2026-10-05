package com.privateplanner.ui

import android.view.Choreographer

private const val AutoScrollReferenceFrameMillis = 16.666667f
// The zones are measured from what can be seen: the heading's bottom edge and the top of
// the navigation bar. Each reaches full speed at its edge, so the finger never has to
// cover the heading or the bar to get there, and both follow the heading's size (font
// scale, status bar) and the navigation mode.
private const val AutoScrollTopReach = 88f
private const val AutoScrollBottomFraction = 0.20f
private const val AutoScrollTopMaxStep = 16f
private const val AutoScrollBottomMaxStep = 42f

// Scrolls the day while a dragged block is held near an edge, a frame at a time, until
// stopped. The zones are fixed when the drag starts.
internal class EdgeAutoScroll(
    private val host: TimelineHost,
    density: Float,
    private val tile: TimeBlockView
) : Choreographer.FrameCallback {
    private val visibleTopPx = host.headerHeightPx.toFloat()
    private val visibleBottomPx = host.visibleBottomPx.toFloat()
    private val topReachPx = AutoScrollTopReach * density
    private val bottomReachPx = (visibleBottomPx - visibleTopPx) * AutoScrollBottomFraction
    private val topMaxStepPx = AutoScrollTopMaxStep * density
    private val bottomMaxStepPx = AutoScrollBottomMaxStep * density
    private var lastFrameNanos = 0L
    private var running = false
    private var step = 0f

    init {
        update()
    }

    // Pointer events choose the speed. Holding a tile in the middle of the screen
    // needs no frame callbacks; entering an edge starts them again.
    fun update() {
        step = if (tile.scrollsAtEdges) TimelineGeometry.edgeAutoScrollDelta(
            pointerViewportY = tile.pointerViewportY,
            visibleTopPx = visibleTopPx,
            visibleBottomPx = visibleBottomPx,
            topReachPx = topReachPx,
            bottomReachPx = bottomReachPx,
            topMaxStepPx = topMaxStepPx,
            bottomMaxStepPx = bottomMaxStepPx
        ) else 0f
        if (step == 0f) stop() else if (!running) {
            running = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameNanos: Long) {
        if (!running) return
        val previousFrameNanos = lastFrameNanos
        lastFrameNanos = frameNanos
        if (previousFrameNanos != 0L) {
            val frameMillis = (frameNanos - previousFrameNanos) / 1_000_000f
            if (host.scrollTimelineBy(step * (frameMillis / AutoScrollReferenceFrameMillis))) tile.edgeScrolled()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
