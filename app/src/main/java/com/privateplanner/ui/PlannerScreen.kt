package com.privateplanner.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.MovePlacement
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import com.privateplanner.postNotificationsGranted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

private val CompactBlockShape = RoundedCornerShape(13.dp)
private val RegularBlockShape = RoundedCornerShape(16.dp)
private val SnackbarShape = RoundedCornerShape(18.dp)
private val SnackbarActionShape = RoundedCornerShape(11.dp)
private val HeaderButtonShape = RoundedCornerShape(10.dp)
@Composable
internal fun PlannerScreen(viewModel: PlannerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val sheet = uiState.sheet
    val currentSnackbar = uiState.snackbar
    val selectedBlocks = uiState.blocks
    val timelineScrollState = rememberScrollState()
    // Only read while placing pinned titles, so a new heading height re-places those
    // tiles instead of recomposing every one.
    val headerHeightPx = remember { mutableIntStateOf(0) }
    val today = LocalCurrentDate.current
    val backgroundSemantics = if (sheet == null) {
        Modifier
    } else {
        Modifier.clearAndSetSemantics { }
    }
    val deleteBlockWithHaptic: (Long) -> Unit = { blockId ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.deleteBlock(blockId)
    }
    PlannerSystemBarsEffect(dimmed = sheet != null)

    // The host only ever shows the current snackbar, so clearing it is enough.
    BackHandler(enabled = sheet != null || currentSnackbar != null || uiState.selectedDate != today) {
        when {
            sheet != null -> viewModel.dismissSheet()
            currentSnackbar != null -> viewModel.clearSnackbar(currentSnackbar.id)
            else -> viewModel.returnToToday()
        }
    }

    // Composed only while there is a snackbar: clearing it cancels this, which takes it
    // off screen, and no effect runs while there is none.
    if (currentSnackbar != null) LaunchedEffect(currentSnackbar.id) {
        val message = currentSnackbar
        val result = snackbarHostState.showSnackbar(
            message = when (message) {
                is PlannerSnackbar.Deleted -> "Deleted"
                is PlannerSnackbar.Message -> message.message
            },
            actionLabel = if (message is PlannerSnackbar.Deleted) "Undo" else null,
            withDismissAction = false,
            duration = SnackbarDuration.Short
        )
        if (message is PlannerSnackbar.Deleted && result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete(message.id)
        } else {
            viewModel.clearSnackbar(message.id)
        }
    }

    // No background: the window's is the paper (see applyPlannerSystemBars).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .axisLockedDaySwipe(
                enabled = sheet == null,
                onPrevious = {
                    viewModel.shiftDay(-1)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
                onNext = {
                    viewModel.shiftDay(1)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            )
    ) {
        Timeline(
            selectedDate = uiState.selectedDate,
            blocks = selectedBlocks,
            scrollState = timelineScrollState,
            headerHeightPx = headerHeightPx,
            takeScrollTarget = viewModel::takeScrollTarget,
            onEmptyTimeTap = viewModel::openCreate,
            onBlockTap = viewModel::openActions,
            onBlockRename = viewModel::openRename,
            onBlockDelete = deleteBlockWithHaptic,
            onBlockMove = viewModel::moveBlock,
            onBlockResize = viewModel::resizeBlock,
            modifier = backgroundSemantics
        )

        TimelineHeader(
            selectedDate = uiState.selectedDate,
            onDateClick = viewModel::openDateJump,
            onContentHeight = { headerHeightPx.intValue = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(backgroundSemantics)
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .then(backgroundSemantics),
            snackbar = { data -> PlannerSnackbar(data) }
        )

        when (sheet) {
            is PlannerSheet.CreateBlock -> BlockInputSheet(
                title = "",
                buttonLabel = "Add",
                onSubmit = viewModel::createBlock,
                onDismiss = viewModel::dismissSheet,
                errorText = uiState.sheetError
            )
            is PlannerSheet.RenameBlock -> selectedBlocks.firstOrNull { it.id == sheet.blockId }?.let { block ->
                BlockInputSheet(
                    title = block.title,
                    buttonLabel = "Rename",
                    onSubmit = viewModel::renameBlock,
                    onDismiss = viewModel::dismissSheet,
                    errorText = uiState.sheetError
                )
            }
            is PlannerSheet.BlockActions -> selectedBlocks.firstOrNull { it.id == sheet.blockId }?.let { block ->
                BlockActionSheet(
                    block = block,
                    onRename = { viewModel.openRename(block.id) },
                    onDelete = { deleteBlockWithHaptic(block.id) },
                    onDismiss = viewModel::dismissSheet
                )
            }
            PlannerSheet.DateJump -> {
                // Registered only while the sheet that asks is open: registering draws a
                // random key, and the first draw seeds SecureRandom, which launch should
                // not wait for.
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) viewModel.setRemindersOn(true) else viewModel.notificationsBlocked()
                }
                DateJumpSheet(
                    selectedDate = uiState.selectedDate,
                    remindersOn = uiState.remindersOn,
                    onToggleReminders = { on ->
                        if (!on || context.postNotificationsGranted()) {
                            viewModel.setRemindersOn(on)
                        } else {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSelect = viewModel::jumpTo,
                    onDismiss = viewModel::dismissSheet
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun PlannerSnackbar(data: SnackbarData) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(PlannerColours.Sheet, SnackbarShape)
            .border(
                width = 1.dp,
                color = PlannerColours.HourLine.copy(alpha = 0.72f),
                shape = SnackbarShape
            )
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(
            text = data.visuals.message,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val actionLabel = data.visuals.actionLabel
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                color = PlannerColours.Delete,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .height(48.dp)
                    .clip(SnackbarActionShape)
                    .background(PlannerColours.Delete.copy(alpha = 0.10f))
                    .clickable(onClick = data::performAction)
                    .padding(horizontal = 12.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }
    }
}

private const val HeadingDatePattern = "EEEE, d MMMM"
internal const val FullDatePattern = "EEEE, d MMMM yyyy"
private val DateFormatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

// Each pattern is parsed once per locale and shared, including with the launch warm-up.
internal fun dateFormatter(pattern: String, locale: Locale): DateTimeFormatter =
    DateFormatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

// Launch's main-thread work that can be done ahead, on the warm-up thread: the palettes
// with their colour spaces and Material's colour tokens, the grid labels, and the
// locale's day and month names (the first format loads them). Whatever is not ready in
// time is done where it was.
internal fun Context.warmUpInterface() {
    val now = TimeSnapper.localNowMillis()
    colourSchemeFor(displayedPaletteForMinute(TimeSnapper.minuteOfDay(now)))
    prepareGridLabels()
    val locale = Locale.getDefault()
    val today = TimeSnapper.dateOf(now)
    today.format(dateFormatter(HeadingDatePattern, locale))
    prepareDateSheet(locale, today)
}

// A single translucent tint spans the status bar and heading. It never becomes
// opaque, so scrolling tiles remain visible behind the system icons too.
private const val HeaderTintAlpha = 0.68f
private val HeaderFadeHeight = 56.dp
private const val ScrimSteps = 32

@Composable
private fun TimelineHeader(
    selectedDate: LocalDate,
    onDateClick: () -> Unit,
    onContentHeight: (Int) -> Unit,
    modifier: Modifier
) {
    val today = LocalCurrentDate.current
    val locale = LocalLocale.current.platformLocale
    val dateFormatter = remember(locale) { dateFormatter(HeadingDatePattern, locale) }
    // Relative days show their date underneath; other years (rare) add the year.
    val (title, subtitle) = remember(selectedDate, today, dateFormatter) {
        val relative = when (selectedDate) {
            today.minusDays(1) -> "Yesterday"
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> null
        }
        when {
            relative != null -> relative to selectedDate.format(dateFormatter)
            selectedDate.year == today.year -> selectedDate.format(dateFormatter) to null
            else -> selectedDate.format(dateFormatter(FullDatePattern, locale)) to null
        }
    }
    val paper = PlannerColours.Paper
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val fadeHeight = HeaderFadeHeight.toPx()
                // Smootherstep meets the constant tint and transparent timeline
                // with zero slope. Cache the brush until size or paper changes;
                // scrolling only redraws this small strip, with no blur buffers.
                val glaze = Brush.verticalGradient(
                    colorStops = Array(ScrimSteps + 1) { index ->
                        val t = index.toFloat() / ScrimSteps
                        val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
                        t to paper.copy(alpha = HeaderTintAlpha * (1f - eased))
                    },
                    startY = size.height - fadeHeight,
                    endY = size.height
                )
                onDrawBehind { drawRect(glaze) }
            }
            .padding(bottom = HeaderFadeHeight)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { onContentHeight(it.height) }
                .statusBarsPadding()
                .padding(top = 4.dp, bottom = 14.dp)
                .clip(HeaderButtonShape)
                .clickable(onClick = onDateClick)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = "Jump date, $title"
                }
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Timeline(
    selectedDate: LocalDate,
    blocks: List<PlannerBlock>,
    scrollState: ScrollState,
    headerHeightPx: IntState,
    takeScrollTarget: () -> Int?,
    onEmptyTimeTap: (Int) -> Unit,
    onBlockTap: (Long) -> Unit,
    onBlockRename: (Long) -> Unit,
    onBlockDelete: (Long) -> Unit,
    onBlockMove: (Long, Int) -> Boolean,
    onBlockResize: (Long, Int) -> Boolean,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val hourHeightPx = with(density) { HourHeight.toPx() }
    val topClearancePx = with(density) { TimelineTopClearance.toPx() }
    val layoutById = remember(blocks) {
        OverlapLayoutCalculator.calculate(blocks)
    }
    // Read when a tap or gesture asks rather than captured, so neither the tap detector nor
    // the tiles' validator changes with the blocks.
    val latestBlocks = rememberUpdatedState(blocks)
    val latestLayoutById = rememberUpdatedState(layoutById)
    // One validator for the timeline's lifetime: a change to one block hands the other
    // tiles no new parameter, so they skip.
    val overlapPolicyForBlock: (Long) -> OverlapPolicy = remember {
        var cachedBlocks: List<PlannerBlock>? = null
        var cachedBlockId = Long.MIN_VALUE
        var cachedPolicy: OverlapPolicy? = null
        fun(blockId: Long): OverlapPolicy {
            val current = latestBlocks.value
            if (current !== cachedBlocks || blockId != cachedBlockId) {
                cachedBlocks = current
                cachedBlockId = blockId
                cachedPolicy = OverlapPolicy.from(current, blockId)
            }
            return checkNotNull(cachedPolicy)
        }
    }
    // Only the grid and the indicator read the minute clock, so a tick never recomposes this.
    val isToday = selectedDate == LocalCurrentDate.current
    // Read only by gestures, when they start, and by the accessibility focus below, so a
    // new viewport height recomposes nothing.
    val viewportHeightPx = remember { mutableIntStateOf(0) }
    // The viewport's bottom edge above the navigation bar, read when a drag starts.
    val navigationBars = WindowInsets.navigationBars
    val visibleBottomPx = remember(navigationBars, density) {
        { viewportHeightPx.intValue - navigationBars.getBottom(density) }
    }
    val accessibilityFocusMinutes = remember(scrollState, hourHeightPx, topClearancePx) {
        derivedStateOf {
            val focusY = scrollState.value +
                viewportHeightPx.intValue * CurrentTimeViewportFraction -
                topClearancePx
            TimeSnapper.minutesFromY(focusY.coerceAtLeast(0f), hourHeightPx)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Runs inside the first layout pass, after the scroll range is measured and
            // before the content is placed: the launch scroll lands in the very first
            // frame, rather than a frame showing midnight and then a jump to now.
            .onSizeChanged { size ->
                viewportHeightPx.intValue = size.height
                if (size.height > 0) {
                    takeScrollTarget()?.let { target ->
                        val targetPx = (topClearancePx + target / 60f * hourHeightPx).roundToInt()
                        val visibleLeadPx = if (isToday) {
                            (size.height * CurrentTimeViewportFraction).roundToInt()
                        } else {
                            0
                        }
                        val maxScrollPx = (topClearancePx + 24f * hourHeightPx - size.height)
                            .roundToInt()
                            .coerceAtLeast(0)
                        val to = (targetPx - visibleLeadPx).coerceIn(0, maxScrollPx)
                        scrollState.dispatchRawDelta((to - scrollState.value).toFloat())
                    }
                }
            }
            .verticalScroll(scrollState)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(top = TimelineTopClearance)
                .height(DayHeight)
                .fillMaxWidth()
                .semantics {
                    val startMinutes = accessibilityFocusMinutes.value
                    val time = TimeFormatter.time(startMinutes)
                    contentDescription = "Day timeline, $time"
                    onClick(label = "Add block at $time") {
                        onEmptyTimeTap(startMinutes)
                        true
                    }
                }
                .timelineTapInput(latestBlocks, latestLayoutById, onEmptyTimeTap)
        ) {
            val timelineWidth = maxWidth
            TimelineGrid(showsNow = isToday)

            for (block in blocks) {
                key(block.id) {
                    TimeBlock(
                        block = block,
                        validatorForBlock = overlapPolicyForBlock,
                        layout = layoutById.getValue(block.id),
                        timelineWidth = timelineWidth,
                        scrollState = scrollState,
                        headerHeightPx = headerHeightPx,
                        visibleBottomPx = visibleBottomPx,
                        hourHeightPx = hourHeightPx,
                        onTap = { onBlockTap(block.id) },
                        onRename = { onBlockRename(block.id) },
                        onDelete = { onBlockDelete(block.id) },
                        onMove = { start -> onBlockMove(block.id, start) },
                        onResize = { onBlockResize(block.id, it) }
                    )
                }
            }

            if (isToday) {
                CurrentTimeIndicator()
            }
        }
    }
}

@Composable
private fun TimeBlock(
    block: PlannerBlock,
    validatorForBlock: (Long) -> OverlapPolicy,
    layout: BlockLayout,
    timelineWidth: Dp,
    scrollState: ScrollState,
    headerHeightPx: IntState,
    visibleBottomPx: () -> Int,
    hourHeightPx: Float,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Boolean,
    onResize: (Int) -> Boolean
) {
    val haptics = LocalHapticFeedback.current
    val latestBlock by rememberUpdatedState(block)
    // The caller keys each tile by block id, so this state never outlives its block.
    var previewStartMinutes by remember { mutableIntStateOf(NoPreviewMinutes) }
    var previewDurationMinutes by remember { mutableIntStateOf(NoPreviewMinutes) }
    // Read by the active drag layer so snapped moves do not recompose the tile body.
    val moveOffsetPx = remember { mutableFloatStateOf(0f) }
    var moveActive by remember { mutableStateOf(false) }
    var resizeActive by remember { mutableStateOf(false) }
    val displayedDurationMinutes = if (previewDurationMinutes != NoPreviewMinutes) {
        previewDurationMinutes
    } else {
        block.durationMinutes
    }

    val columnWidth = (timelineWidth - TimelineGutter - TimelineEndPadding).coerceAtLeast(1.dp) /
        layout.columnCount.coerceAtLeast(1)
    val left = TimelineGutter + columnWidth * layout.columnIndex
    val width = (columnWidth - BlockColumnGap).coerceAtLeast(MinimumTouchTarget)
    val baseTop = heightForMinutes(block.startMinutes)
    val baseHeight = heightForMinutes(block.durationMinutes)
    val baseTouchHeight = baseHeight.coerceAtLeast(MinimumTouchTarget)
    val touchTop = centredTouchTop(baseTop, baseHeight)
    val visualHeight = heightForMinutes(displayedDurationMinutes)
    val baseVisualOffset = baseTop - touchTop
    val touchHeight = maxOf(baseTouchHeight, baseVisualOffset + visualHeight)
        .coerceAtMost((DayHeight - touchTop).coerceAtLeast(MinimumTouchTarget))
    val latestVisualOffset by rememberUpdatedState(baseVisualOffset)
    val compact = displayedDurationMinutes <= QuickResizeMaxDurationMinutes
    val background = Color(blockBackgroundArgb(block.startMinutes, layout.columnIndex))
    val blockShape = if (compact) CompactBlockShape else RegularBlockShape
    val rangeTextProvider: () -> String = {
        val labelStart = if (previewStartMinutes != NoPreviewMinutes) previewStartMinutes else block.startMinutes
        val labelDuration =
            if (previewDurationMinutes != NoPreviewMinutes) previewDurationMinutes else block.durationMinutes
        TimeFormatter.range(labelStart, labelDuration)
    }
    val durationText = remember(displayedDurationMinutes) {
        TimeFormatter.duration(displayedDurationMinutes)
    }
    val active = moveActive || resizeActive
    // Only long tiles pin their title; the rest carry no offset node at all.
    val titleFollowOffset: (Density.() -> Int)? = if (visualHeight < LongTitlePinMinHeight) {
        null
    } else {
        {
            val followStart =
                if (previewStartMinutes != NoPreviewMinutes) previewStartMinutes else block.startMinutes
            titleFollowOffsetPx(
                scrollPx = scrollState.value,
                blockTop = heightForMinutes(followStart),
                visualHeight = visualHeight,
                headerBottomPx = headerHeightPx.intValue
            )
        }
    }

    fun moveBy(deltaMinutes: Int): Boolean {
        val targetStart = TimeSnapper.clampStart(
            block.startMinutes + deltaMinutes,
            block.durationMinutes
        )
        if (targetStart == block.startMinutes) return false
        return validatorForBlock(block.id).canPlace(targetStart, block.durationMinutes) &&
            onMove(targetStart)
    }

    fun resizeBy(deltaMinutes: Int): Boolean {
        val targetDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            block.durationMinutes + deltaMinutes
        )
        if (targetDuration == block.durationMinutes) return false
        return validatorForBlock(block.id).canPlace(
            startMinutes = block.startMinutes,
            durationMinutes = targetDuration
        ) &&
            onResize(targetDuration)
    }

    // One node: the touch target's modifiers first, then the visual tile inside it, measured
    // and placed as a child box would be (height released to at most the target's, at its
    // top) with no second layout node.
    TimeBlockForeground(
        background = background,
        shape = blockShape,
        active = active,
        title = block.title,
        rangeText = rangeTextProvider,
        durationText = durationText,
        tileWidth = width,
        visualHeight = visualHeight,
        titleFollowOffset = titleFollowOffset,
        modifier = Modifier
            .offset(x = left, y = touchTop)
            .width(width)
            .height(touchHeight)
            .then(if (active) Modifier.zIndex(2f) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "${block.title}, ${TimeFormatter.range(block.startMinutes, displayedDurationMinutes, " to ")}, $durationText. Actions: Rename, Delete."
                onClick(label = "Open actions") {
                    onTap()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("Rename") {
                        onRename()
                        true
                    },
                    CustomAccessibilityAction("Delete") {
                        onDelete()
                        true
                    },
                    CustomAccessibilityAction("Move earlier 5 minutes") {
                        moveBy(-TimeSnapper.SnapMinutes)
                    },
                    CustomAccessibilityAction("Move later 5 minutes") {
                        moveBy(TimeSnapper.SnapMinutes)
                    },
                    CustomAccessibilityAction("Shorten 5 minutes") {
                        resizeBy(-TimeSnapper.SnapMinutes)
                    },
                    CustomAccessibilityAction("Lengthen 5 minutes") {
                        resizeBy(TimeSnapper.SnapMinutes)
                    }
                )
            }
            .blockMoveInput(
                blockId = block.id,
                latestBlock = { latestBlock },
                latestValidator = { validatorForBlock(block.id) },
                latestVisualOffset = { latestVisualOffset },
                hourHeightPx = hourHeightPx,
                scrollState = scrollState,
                headerHeightPx = headerHeightPx,
                visibleBottomPx = visibleBottomPx,
                haptics = haptics,
                onTap = onTap,
                onMoveActiveChange = { moveActive = it },
                onMovePreview = { previewStartMinutes = it ?: NoPreviewMinutes },
                onMoveVisualOffsetPx = { moveOffsetPx.floatValue = it },
                onMove = onMove,
                onResizeActiveChange = { resizeActive = it },
                onResizePreview = {
                    previewDurationMinutes = it ?: NoPreviewMinutes
                },
                onResize = onResize
            )
            .wrapContentHeight(Alignment.Top)
            .offset(y = baseVisualOffset)
            .height(visualHeight)
            .dragTranslationLayer(moveActive, moveOffsetPx)
    )
}

// Blocks and pixel geometry are read at tap time, so nothing restarts the detector: a
// change of blocks, such as the write after a drop, cannot drop a tap in progress.
private fun Modifier.timelineTapInput(
    blocks: State<List<PlannerBlock>>,
    layoutById: State<Map<Long, BlockLayout>>,
    onEmptyTimeTap: (Int) -> Unit
): Modifier {
    return pointerInput(onEmptyTimeTap) {
        detectTapGestures { offset ->
            val hourHeightPx = HourHeight.toPx()
            val hitBlock = TimelineGeometry.hitTestBlock(
                x = offset.x,
                y = offset.y,
                blocks = blocks.value,
                layoutById = layoutById.value,
                timelineWidthPx = size.width.toFloat(),
                gutterPx = TimelineGutter.toPx(),
                timelineEndPaddingPx = TimelineEndPadding.toPx(),
                blockColumnGapPx = BlockColumnGap.toPx(),
                hourHeightPx = hourHeightPx,
                minimumTouchTargetPx = MinimumTouchTarget.toPx()
            )
            if (!hitBlock) {
                onEmptyTimeTap(TimeSnapper.minutesFromY(offset.y, hourHeightPx))
            }
        }
    }
}

private fun Modifier.blockMoveInput(
    blockId: Long,
    latestBlock: () -> PlannerBlock,
    latestValidator: () -> OverlapPolicy,
    latestVisualOffset: () -> Dp,
    hourHeightPx: Float,
    scrollState: ScrollState,
    headerHeightPx: IntState,
    visibleBottomPx: () -> Int,
    haptics: HapticFeedback,
    onTap: () -> Unit,
    onMoveActiveChange: (Boolean) -> Unit,
    onMovePreview: (Int?) -> Unit,
    onMoveVisualOffsetPx: (Float) -> Unit,
    onMove: (Int) -> Boolean,
    onResizeActiveChange: (Boolean) -> Unit,
    onResizePreview: (Int?) -> Unit,
    onResize: (Int) -> Boolean
): Modifier {
    return pointerInput(blockId, hourHeightPx) {
        coroutineScope gestureScope@{
            awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val initial = latestBlock()
            val visualOffsetPx = latestVisualOffset().toPx()
            val downYInVisual = down.position.y - visualOffsetPx
            val visualHeightPx = initial.durationMinutes / 60f * hourHeightPx
            val startsInResizeZone = TimelineGeometry.isInResizeZone(
                x = down.position.x,
                yInVisual = downYInVisual,
                blockWidthPx = size.width.toFloat(),
                visualHeightPx = visualHeightPx,
                durationMinutes = initial.durationMinutes,
                laneFraction = ResizeLaneFraction,
                minimumTouchTargetPx = MinimumTouchTarget.toPx(),
                quickResizeMaxDurationMinutes = QuickResizeMaxDurationMinutes
            )
            val startsOnResizeHandle = TimelineGeometry.isInResizeHandle(
                x = down.position.x,
                yInVisual = downYInVisual,
                blockWidthPx = size.width.toFloat(),
                visualHeightPx = visualHeightPx,
                handleHitWidthPx = ResizeHandleLongPressHitWidth.toPx(),
                handleHitHeightPx = ResizeHandleLongPressHitHeight.toPx(),
                handleBottomPaddingPx = ResizeHandleBottomPadding.toPx()
            )
            val touchSlop = viewConfiguration.touchSlop
            val holdStillThreshold = maxOf(touchSlop * 1.25f, HoldStillTolerance.toPx())
            val holdStillThresholdSquared = holdStillThreshold * holdStillThreshold
            val quickResizeDragThreshold = maxOf(
                touchSlop,
                QuickResizeDragThreshold.toPx()
            )
            var preHoldDrag = Offset.Zero
            var resizeBeforeHold = false
            val tapped = withTimeoutOrNull(BlockMoveHoldMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        return@withTimeoutOrNull false
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (change.isConsumed) {
                        return@withTimeoutOrNull false
                    }
                    if (!change.pressed) {
                        change.consume()
                        return@withTimeoutOrNull true
                    }
                    preHoldDrag += change.positionChange()
                    val verticalDrag = abs(preHoldDrag.y) > abs(preHoldDrag.x)
                    val usefulResizeDrag = preHoldDrag.y > 0f ||
                        initial.durationMinutes > TimeSnapper.MinimumDurationMinutes
                    val potentialQuickResize = startsInResizeZone &&
                        verticalDrag &&
                        usefulResizeDrag
                    if (abs(preHoldDrag.y) >= quickResizeDragThreshold ||
                        abs(preHoldDrag.x) >= quickResizeDragThreshold
                    ) {
                        if (potentialQuickResize) {
                            resizeBeforeHold = true
                            change.consume()
                            return@withTimeoutOrNull false
                        }
                    }
                    if (
                        !potentialQuickResize &&
                        preHoldDrag.getDistanceSquared() > holdStillThresholdSquared
                    ) {
                        return@withTimeoutOrNull false
                    }
                }
            }

            val cancelledBeforeHold = !resizeBeforeHold &&
                (tapped == false ||
                    (tapped == null &&
                        preHoldDrag.getDistanceSquared() > holdStillThresholdSquared))
            val resizeAfterHandleHold = tapped == null && startsOnResizeHandle && !cancelledBeforeHold

            when {
                tapped == true -> {
                    onTap()
                    return@awaitEachGesture
                }
                resizeBeforeHold || resizeAfterHandleHold -> {
                    if (resizeAfterHandleHold) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    runResizeGesture(
                        gestureScope = this@gestureScope,
                        pointerId = down.id,
                        initial = initial,
                        validator = latestValidator(),
                        initialDy = preHoldDrag.y,
                        initialPointerY = downYInVisual,
                        hourHeightPx = hourHeightPx,
                        scrollState = scrollState,
                        visibleTopPx = headerHeightPx.intValue,
                        visibleBottomPx = visibleBottomPx(),
                        haptics = haptics,
                        onResizeActiveChange = onResizeActiveChange,
                        onResizePreview = onResizePreview,
                        onResize = onResize
                    )
                    return@awaitEachGesture
                }
                cancelledBeforeHold -> return@awaitEachGesture
            }

            val validator = latestValidator()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onMoveActiveChange(true)

            val initialScroll = scrollState.value
            val initialBlockTopPx = TimelineTopClearance.toPx() + initial.startMinutes / 60f * hourHeightPx
            val initialPointerViewportY = initialBlockTopPx - initialScroll + downYInVisual
            var totalPointerDy = preHoldDrag.y
            var pointerViewportY = initialPointerViewportY + totalPointerDy
            var lastSnappedStart = initial.startMinutes
            var lastSavableStart = initial.startMinutes
            var hasDraggedAfterHold = false
            var cancelled = false
            var autoScrollJob: Job? = null
            fun updateMoveFromGesture() {
                val effectiveDy = totalPointerDy + (scrollState.value - initialScroll)
                val snapped = TimeSnapper.clampStart(
                    initial.startMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourHeightPx),
                    initial.durationMinutes
                )
                onMoveVisualOffsetPx((snapped - initial.startMinutes) / 60f * hourHeightPx)
                if (snapped != lastSnappedStart) {
                    lastSnappedStart = snapped
                    onMovePreview(snapped)
                    if (haptics.tickFor(validator.placement(snapped, initial.durationMinutes))) {
                        lastSavableStart = snapped
                    }
                }
            }

            try {
                updateMoveFromGesture()
                autoScrollJob = this@gestureScope.launchEdgeAutoScroll(
                    pointerViewportY = { pointerViewportY },
                    visibleTopPx = headerHeightPx.intValue,
                    visibleBottomPx = visibleBottomPx(),
                    scrollState = scrollState,
                    density = this,
                    enabled = { hasDraggedAfterHold },
                    onScrolled = ::updateMoveFromGesture
                )

                cancelled = !trackDrag(down.id) { dy ->
                    totalPointerDy += dy
                    pointerViewportY = initialPointerViewportY + totalPointerDy
                    if (!hasDraggedAfterHold && abs(totalPointerDy) > viewConfiguration.touchSlop) {
                        hasDraggedAfterHold = true
                    }
                    updateMoveFromGesture()
                }
            } catch (throwable: CancellationException) {
                cancelled = true
                throw throwable
            } finally {
                autoScrollJob?.cancel()
                val hasSavableDrop = lastSavableStart != initial.startMinutes
                if (!cancelled && hasSavableDrop) {
                    onMove(lastSavableStart)
                }
                onMoveActiveChange(false)
                onMovePreview(null)
                onMoveVisualOffsetPx(0f)
            }
            }
        }
    }
}

// Follows one pointer's vertical movement until it lifts (true), or until the gesture is
// abandoned to a second finger, a lost pointer or another consumer (false).
private suspend inline fun AwaitPointerEventScope.trackDrag(pointerId: PointerId, onDrag: (Float) -> Unit): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.any { it.id != pointerId && it.pressed }) return false
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (!change.pressed) return true
        if (change.isConsumed) return false
        onDrag(change.position.y - change.previousPosition.y)
        change.consume()
    }
}

// Every snapped step ticks unless the spot is unusable; only a savable one may be dropped on.
private fun HapticFeedback.tickFor(placement: MovePlacement): Boolean {
    if (placement != MovePlacement.Invalid) performHapticFeedback(HapticFeedbackType.TextHandleMove)
    return placement == MovePlacement.Savable
}

private suspend fun AwaitPointerEventScope.runResizeGesture(
    gestureScope: CoroutineScope,
    pointerId: PointerId,
    initial: PlannerBlock,
    validator: OverlapPolicy,
    initialDy: Float,
    initialPointerY: Float,
    hourHeightPx: Float,
    scrollState: ScrollState,
    visibleTopPx: Int,
    visibleBottomPx: Int,
    haptics: HapticFeedback,
    onResizeActiveChange: (Boolean) -> Unit,
    onResizePreview: (Int?) -> Unit,
    onResize: (Int) -> Boolean
) {
    val initialScroll = scrollState.value
    val initialBlockTopPx = TimelineTopClearance.toPx() + initial.startMinutes / 60f * hourHeightPx
    val initialPointerViewportY = initialBlockTopPx - initialScroll + initialPointerY
    var totalDy = initialDy
    var pointerViewportY = initialPointerViewportY + totalDy
    var lastSnappedDuration = initial.durationMinutes
    var lastSavableDuration = initial.durationMinutes
    var cancelled = false
    var autoScrollJob: Job? = null
    onResizeActiveChange(true)

    fun resizeTo() {
        val effectiveDy = totalDy + (scrollState.value - initialScroll)
        val snappedDuration = TimeSnapper.clampDuration(
            initial.startMinutes,
            initial.durationMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourHeightPx)
        )
        if (snappedDuration != lastSnappedDuration) {
            lastSnappedDuration = snappedDuration
            onResizePreview(snappedDuration)
            if (haptics.tickFor(validator.placement(initial.startMinutes, snappedDuration))) {
                lastSavableDuration = snappedDuration
            }
        }
    }

    try {
        resizeTo()
        autoScrollJob = gestureScope.launchEdgeAutoScroll(
            pointerViewportY = { pointerViewportY },
            visibleTopPx = visibleTopPx,
            visibleBottomPx = visibleBottomPx,
            scrollState = scrollState,
            density = this,
            onScrolled = ::resizeTo
        )

        cancelled = !trackDrag(pointerId) { dy ->
            totalDy += dy
            pointerViewportY = initialPointerViewportY + totalDy
            resizeTo()
        }
    } catch (throwable: CancellationException) {
        cancelled = true
        throw throwable
    } finally {
        autoScrollJob?.cancel()
        if (!cancelled && lastSavableDuration != initial.durationMinutes) {
            onResize(lastSavableDuration)
        }
        onResizeActiveChange(false)
        onResizePreview(null)
    }
}
