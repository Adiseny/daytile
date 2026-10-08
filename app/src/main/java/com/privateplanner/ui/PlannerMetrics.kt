package com.privateplanner.ui

// Lengths in dp.
internal const val HourHeight = 120f
internal const val DayHeight = HourHeight * 24
internal const val LongTitlePinMinHeight = 240f
internal const val TimelineTopClearance = 86f
internal const val TimelineGutter = 72f
internal const val TimelineEndPadding = 10f
internal const val BlockColumnGap = 4f
internal const val FiveMinuteTickLength = 10f
internal const val QuarterHourTickLength = 18f
internal const val GridStrokeWidth = 1f
internal const val MinimumTouchTarget = 48f
internal const val HourLabelHeight = 28f
internal const val HalfHourLabelHeight = 22f
internal const val BlockMoveHoldMillis = 350L
internal const val QuickResizeMaxDurationMinutes = 30
internal const val ResizeLaneFraction = 0.24f
internal const val FiveMinuteTickAlpha = 0.72f
internal const val CurrentTimeViewportFraction = 0.32f
internal const val NoPreviewMinutes = Int.MIN_VALUE
internal const val HoldStillTolerance = 14f
// What shows of the day above the sheet beneath where the next block of a row will start.
internal const val RowClearance = 28f
internal const val QuickResizeDragThreshold = 10f
internal const val ResizeHandleWidth = 28f
internal const val ResizeHandleHeight = 3f
internal const val ResizeHandleBottomPadding = 3f
internal const val ResizeHandleLongPressHitWidth = 44f
internal const val ResizeHandleLongPressHitHeight = 18f
// A handle at rest, and while its edge is held: all of a resize that shows.
internal const val ResizeHandleAlpha = 0.18f
internal const val ResizeHandleHeldAlpha = 0.6f
// How much slower the day goes by under an edge held at the screen's edge than under a block
// carried there: an edge is placed to the step, a block to the hour.
internal const val ResizeScrollSpeed = 0.25f
// A touch this soon after the day last scrolled is a scroll's, and resizes nothing.
internal const val ScrollOwnsTouchMillis = 300L

// The week: seven day columns between a margin for the hours and the screen's edge, each a
// stack of bands three hours tall in the colour blocks take in those hours.
internal const val WeekGutter = 40f
internal const val WeekEndPadding = 7f
internal const val WeekDayGap = 5f
internal const val WeekTileInset = 2f
internal const val WeekTileGap = 2f
internal const val WeekTitleInset = 3f
// The lower edge of a week's tile, which a hold lengthens and shortens it by, up to two
// fifths of the tile, and the handle shown while it is held.
internal const val WeekResizeEdge = 16f
internal const val WeekHandleWidth = 16f
internal const val WeekTileRadius = 8f
internal const val WeekBandRadius = 6f
internal const val WeekBandHours = 3
internal const val WeekBandAlpha = 0.15f
// The week opens with this hour at the top, and an hour is as tall as fits the rest of the
// day on the screen beneath it, but never shorter than this.
internal const val WeekFirstHour = 6
internal const val WeekMinHourHeight = 40f
internal const val WeekSnapMinutes = 15
// The week's dates follow the system's text size this far and no further: a date has a
// column's width and no more.
internal const val WeekLargestText = 1.3f
