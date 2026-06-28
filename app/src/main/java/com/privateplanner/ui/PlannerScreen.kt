package com.privateplanner.ui

import android.provider.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import com.privateplanner.domain.DateLabelFormatter
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeOfDayColourMapper
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

@Composable
fun PlannerScreen(viewModel: PlannerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val currentSnackbar = uiState.snackbar
    val selectedBlocks = uiState.blocksByDate[uiState.selectedDate].orEmpty()
    val selectedBlockId = when (val sheet = uiState.sheet) {
        is PlannerSheet.BlockActions -> sheet.blockId
        is PlannerSheet.RenameBlock -> sheet.blockId
        else -> null
    }
    val timelineScrollState = rememberScrollState()
    val reduceMotion = rememberReduceMotion()
    val hasBackInterception = uiState.sheet != null ||
        currentSnackbar != null ||
        uiState.selectedDate != LocalDate.now() ||
        snackbarHostState.currentSnackbarData != null

    BackHandler(enabled = hasBackInterception) {
        when {
            uiState.sheet != null -> viewModel.dismissSheet()
            snackbarHostState.currentSnackbarData != null -> {
                snackbarHostState.currentSnackbarData?.dismiss()
                currentSnackbar?.let { viewModel.clearSnackbar(it.id) }
            }
            currentSnackbar != null -> viewModel.clearSnackbar(currentSnackbar.id)
            uiState.selectedDate != LocalDate.now() -> viewModel.returnToToday()
        }
    }

    LaunchedEffect(currentSnackbar?.id) {
        val message = currentSnackbar ?: return@LaunchedEffect
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlannerColours.Paper)
            .axisLockedDaySwipe(
                enabled = uiState.sheet == null,
                onPrevious = viewModel::previousDay,
                onNext = viewModel::nextDay
            )
    ) {
        AnimatedContent(
            targetState = uiState.selectedDate,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val direction = if (targetState.isAfter(initialState)) 1 else -1
                    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
                        durationMillis = DayTransitionMillis,
                        easing = FastOutSlowInEasing
                    )
                    slideInHorizontally(animationSpec = slideSpec) { width -> direction * width } togetherWith
                        slideOutHorizontally(animationSpec = slideSpec) { width -> -direction * width }
                }
            },
            label = "day-transition",
            modifier = Modifier.fillMaxSize()
        ) { animatedDate ->
            val pageBlocks = uiState.blocksByDate[animatedDate].orEmpty()
            val pageLoaded = if (animatedDate == uiState.selectedDate) {
                uiState.selectedDateLoaded
            } else {
                uiState.blocksByDate.containsKey(animatedDate)
            }
            val pageScrollTarget = if (animatedDate == uiState.selectedDate) {
                uiState.scrollTargetMinutes
            } else {
                null
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (pageLoaded) {
                    Timeline(
                        selectedDate = animatedDate,
                        blocks = pageBlocks,
                        selectedBlockId = if (animatedDate == uiState.selectedDate) selectedBlockId else null,
                        scrollState = timelineScrollState,
                        scrollTargetMinutes = pageScrollTarget,
                        onEmptyTimeTap = viewModel::openCreate,
                        onBlockTap = viewModel::openActions,
                        onBlockRename = viewModel::openRename,
                        onBlockDelete = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteBlock(it)
                        },
                        onBlockMove = viewModel::moveBlock,
                        onBlockResize = viewModel::resizeBlock,
                        onScrollTargetConsumed = viewModel::consumeScrollTarget,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                TimelineHeader(
                    selectedDate = animatedDate,
                    onDateClick = viewModel::openDateJump,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(5f)
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            snackbar = { data -> PlannerSnackbar(data) }
        )

        when (val sheet = uiState.sheet) {
            is PlannerSheet.CreateBlock -> BlockInputSheet(
                title = "",
                buttonLabel = "Add",
                onSubmit = viewModel::createBlock,
                onDismiss = viewModel::dismissSheet,
                errorText = uiState.createError
            )
            is PlannerSheet.RenameBlock -> {
                val block = selectedBlocks.firstOrNull { it.id == sheet.blockId }
                if (block != null) {
                    BlockInputSheet(
                        title = block.title,
                        buttonLabel = "Rename",
                        selectAll = true,
                        onSubmit = viewModel::renameBlock,
                        onDismiss = viewModel::dismissSheet,
                        errorText = uiState.createError
                    )
                }
            }
            is PlannerSheet.BlockActions -> {
                val block = selectedBlocks.firstOrNull { it.id == sheet.blockId }
                if (block != null) {
                    BlockActionSheet(
                        block = block,
                        onRename = { viewModel.openRename(block.id) },
                        onDelete = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteBlock(block.id)
                        },
                        onDismiss = viewModel::dismissSheet
                    )
                }
            }
            PlannerSheet.DateJump -> DateJumpSheet(
                selectedDate = uiState.selectedDate,
                onSelect = viewModel::jumpTo,
                onDismiss = viewModel::dismissSheet
            )
            null -> Unit
        }
    }
}

@Composable
private fun PlannerSnackbar(data: SnackbarData) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = PlannerColours.Sheet.copy(alpha = 0.96f),
        contentColor = PlannerColours.PrimaryText,
        shape = shape,
        shadowElevation = 8.dp,
        modifier = Modifier.border(
            width = 1.dp,
            color = PlannerColours.HourLine.copy(alpha = 0.72f),
            shape = shape
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = data.visuals.message,
                color = PlannerColours.PrimaryText,
                fontFamily = DaytileFontFamily,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val actionLabel = data.visuals.actionLabel
            if (actionLabel != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(PlannerColours.Delete.copy(alpha = 0.10f))
                        .clickable(onClick = data::performAction)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = actionLabel,
                        color = PlannerColours.Delete,
                        fontFamily = DaytileFontFamily,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineHeader(
    selectedDate: LocalDate,
    onDateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = DateLabelFormatter.primaryLabel(selectedDate)
    val subtitle = DateLabelFormatter.secondaryLabel(selectedDate)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to PlannerColours.Paper,
                        0.72f to PlannerColours.Paper,
                        0.9f to PlannerColours.Paper.copy(alpha = 0.72f),
                        1f to PlannerColours.Paper.copy(alpha = 0f)
                    )
                )
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 4.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDateClick)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = "Jump date, $title"
                }
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 22.sp,
                    lineHeight = 27.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = PlannerColours.MutedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    ),
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
    selectedBlockId: Long?,
    scrollState: ScrollState,
    scrollTargetMinutes: Int?,
    onEmptyTimeTap: (Int) -> Unit,
    onBlockTap: (Long) -> Unit,
    onBlockRename: (Long) -> Unit,
    onBlockDelete: (Long) -> Unit,
    onBlockMove: (Long, Int) -> Boolean,
    onBlockResize: (Long, Int) -> Boolean,
    onScrollTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hourHeightPx = with(density) { HourHeight.toPx() }
    val gutterPx = with(density) { TimelineGutter.toPx() }
    val topClearancePx = with(density) { TimelineTopClearance.toPx() }
    val minimumTouchTargetPx = with(density) { MinimumTouchTarget.toPx() }
    val layoutById = remember(blocks) {
        OverlapLayoutCalculator.calculate(blocks)
    }
    val sortedBlocks = remember(blocks) {
        blocks.sortedWith(PlannerBlockOrder)
    }
    val occupancyById = remember(blocks) {
        blocks.associate { block ->
            block.id to DayOccupancy.from(blocks, block.id)
        }
    }
    val currentTimeMinutes = LocalCurrentMinuteOfDay.current
    val currentDate = remember(currentTimeMinutes) { LocalDate.now() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val isToday = selectedDate == currentDate

    LaunchedEffect(scrollTargetMinutes, viewportHeightPx, hourHeightPx, topClearancePx) {
        val target = scrollTargetMinutes
        if (target != null && viewportHeightPx > 0) {
            val targetPx = (topClearancePx + target / 60f * hourHeightPx).roundToInt()
            val visibleLeadPx = if (selectedDate == LocalDate.now()) {
                (viewportHeightPx * 0.32f).roundToInt()
            } else {
                0
            }
            val maxScrollPx = (topClearancePx + 24f * hourHeightPx - viewportHeightPx)
                .roundToInt()
                .coerceAtLeast(0)
            scrollState.scrollTo((targetPx - visibleLeadPx).coerceIn(0, maxScrollPx))
            onScrollTargetConsumed()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportHeightPx = it.height }
            .verticalScroll(scrollState)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .height(TimelineTopClearance + DayHeight)
                .fillMaxWidth()
                .background(PlannerColours.Paper)
        ) {
            val timelineMaxWidth = maxWidth
            val timelineWidthPx = with(density) { maxWidth.toPx() }

            Box(
                modifier = Modifier
                    .offset(y = TimelineTopClearance)
                    .height(DayHeight)
                    .fillMaxWidth()
                    .background(PlannerColours.Paper)
                    .timelineTapInput(
                        blocks = blocks,
                        layoutById = layoutById,
                        timelineWidthPx = timelineWidthPx,
                        gutterPx = gutterPx,
                        hourHeightPx = hourHeightPx,
                        minimumTouchTargetPx = minimumTouchTargetPx,
                        onEmptyTimeTap = onEmptyTimeTap
                    )
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                ) {
                    TimelineGrid(modifier = Modifier.fillMaxSize())
                    TimeLabels(modifier = Modifier.fillMaxSize())
                }

                sortedBlocks.forEach { block ->
                    val layout = layoutById[block.id] ?: BlockLayout(0, 1)
                    key(block.id) {
                        TimeBlock(
                            block = block,
                            validator = occupancyById[block.id] ?: DayOccupancy.from(blocks, block.id),
                            selected = selectedBlockId == block.id,
                            layout = layout,
                            timelineWidth = timelineMaxWidth,
                            scrollState = scrollState,
                            viewportHeightPx = viewportHeightPx,
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
                    CurrentTimeIndicator(currentTimeMinutes)
                }
            }
        }
    }
}

@Composable
private fun TimeBlock(
    block: PlannerBlock,
    validator: DayOccupancy,
    selected: Boolean,
    layout: BlockLayout,
    timelineWidth: Dp,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    hourHeightPx: Float,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Boolean,
    onResize: (Int) -> Boolean
) {
    val haptics = LocalHapticFeedback.current
    val latestBlock by rememberUpdatedState(block)
    val latestValidator by rememberUpdatedState(validator)
    var previewStartMinutes by remember(block.id) { mutableIntStateOf(NoPreviewMinutes) }
    var previewDurationMinutes by remember(block.id) { mutableIntStateOf(NoPreviewMinutes) }
    // Read by graphicsLayer so snapped moves can update without recomposing the tile body.
    val moveOffsetPx = remember(block.id) { mutableFloatStateOf(0f) }
    var moveActive by remember(block.id) { mutableStateOf(false) }
    var resizeActive by remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(block.startMinutes, moveActive) {
        if (!moveActive && previewStartMinutes == block.startMinutes) {
            previewStartMinutes = NoPreviewMinutes
        }
    }
    LaunchedEffect(block.durationMinutes, resizeActive) {
        if (!resizeActive && previewDurationMinutes == block.durationMinutes) {
            previewDurationMinutes = NoPreviewMinutes
        }
    }
    val hasMovePreview by remember(block.id) {
        derivedStateOf { previewStartMinutes != NoPreviewMinutes }
    }
    val hasResizePreview by remember(block.id) {
        derivedStateOf { previewDurationMinutes != NoPreviewMinutes }
    }
    val displayedDurationMinutes = if (hasResizePreview) previewDurationMinutes else block.durationMinutes

    val columnWidth = (timelineWidth - TimelineGutter - 10.dp).coerceAtLeast(1.dp) /
        layout.columnCount.coerceAtLeast(1)
    val left = TimelineGutter + columnWidth * layout.columnIndex
    val width = (columnWidth - 4.dp).coerceAtLeast(MinimumTouchTarget)
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
    val background = remember(block.startMinutes, layout.columnIndex) {
        Color(TimeOfDayColourMapper.backgroundArgb(block.startMinutes, layout.columnIndex))
    }
    val blockCornerRadius = if (compact) 13.dp else 16.dp
    val blockShape = RoundedCornerShape(blockCornerRadius)
    val resizeLaneWidth = (width * ResizeLaneFraction).coerceAtLeast(MinimumTouchTarget).coerceAtMost(width)
    val resizeTouchOffset = (baseVisualOffset + visualHeight - MinimumTouchTarget)
        .coerceIn(0.dp, (touchHeight - MinimumTouchTarget).coerceAtLeast(0.dp))
    val rangeTextProvider: () -> String = {
        val labelStart = if (previewStartMinutes != NoPreviewMinutes) previewStartMinutes else block.startMinutes
        val labelDuration =
            if (previewDurationMinutes != NoPreviewMinutes) previewDurationMinutes else block.durationMinutes
        TimeFormatter.range(labelStart, labelDuration)
    }
    val durationText = remember(displayedDurationMinutes) {
        TimeFormatter.duration(displayedDurationMinutes)
    }
    val movingGlass = moveActive || hasMovePreview || hasResizePreview || resizeActive
    val titleFollowOffset: Density.() -> Int = if (visualHeight < LongTitlePinMinHeight) {
        { 0 }
    } else {
        {
            val followStart =
                if (previewStartMinutes != NoPreviewMinutes) previewStartMinutes else block.startMinutes
            titleFollowOffsetPx(scrollState.value, heightForMinutes(followStart), visualHeight)
        }
    }

    fun moveBy(deltaMinutes: Int): Boolean {
        val targetStart = TimeSnapper.clampStart(
            block.startMinutes + deltaMinutes,
            block.durationMinutes
        )
        if (targetStart == block.startMinutes) return false
        return latestValidator.placement(targetStart, block.durationMinutes) == MovePlacement.Savable &&
            onMove(targetStart)
    }

    fun resizeBy(deltaMinutes: Int): Boolean {
        val targetDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            block.durationMinutes + deltaMinutes
        )
        if (targetDuration == block.durationMinutes) return false
        return latestValidator.canPlace(startMinutes = block.startMinutes, durationMinutes = targetDuration) &&
            onResize(targetDuration)
    }

    Box(
        modifier = Modifier
            .offset(x = left, y = touchTop)
            .width(width)
            .height(touchHeight)
            .zIndex(if (movingGlass) 2f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = "${block.title}, ${TimeFormatter.spokenRange(block.startMinutes, displayedDurationMinutes)}, $durationText. Actions: Rename, Delete."
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
                latestValidator = { latestValidator },
                latestVisualOffset = { latestVisualOffset },
                hourHeightPx = hourHeightPx,
                scrollState = scrollState,
                viewportHeightPx = viewportHeightPx,
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
    ) {
        Box(
            modifier = Modifier
                .offset(y = baseVisualOffset)
                .fillMaxWidth()
                .height(visualHeight)
                .liftedGlassLayer(blockShape, movingGlass, moveActive, moveOffsetPx)
                .clip(blockShape)
        ) {
            if (!movingGlass) {
                TimelineGlassBackdrop(
                    timelineWidth = timelineWidth,
                    tileLeft = left,
                    tileTop = baseTop,
                    shape = blockShape,
                    compact = compact
                )
            }

            TimeBlockForeground(
                background = background,
                blockCornerRadius = blockCornerRadius,
                compact = compact,
                movingGlass = movingGlass,
                selected = selected,
                title = block.title,
                rangeText = rangeTextProvider,
                durationText = durationText,
                tileWidth = width,
                visualHeight = visualHeight,
                titleFollowOffset = titleFollowOffset
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = resizeTouchOffset)
                .snappedMoveLayer(moveActive, moveOffsetPx)
                .width(resizeLaneWidth)
                .height(MinimumTouchTarget)
                .semantics {
                    contentDescription = "Resize ${block.title}"
                }
        )
    }
}

private fun Modifier.timelineTapInput(
    blocks: List<PlannerBlock>,
    layoutById: Map<Long, BlockLayout>,
    timelineWidthPx: Float,
    gutterPx: Float,
    hourHeightPx: Float,
    minimumTouchTargetPx: Float,
    onEmptyTimeTap: (Int) -> Unit
): Modifier {
    return pointerInput(blocks, layoutById, timelineWidthPx, gutterPx, hourHeightPx, minimumTouchTargetPx) {
        detectTapGestures { offset ->
            val hitBlock = TimelineGeometry.hitTestBlock(
                x = offset.x,
                y = offset.y,
                blocks = blocks,
                layoutById = layoutById,
                timelineWidthPx = timelineWidthPx,
                gutterPx = gutterPx,
                hourHeightPx = hourHeightPx,
                minimumTouchTargetPx = minimumTouchTargetPx
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
    latestValidator: () -> DayOccupancy,
    latestVisualOffset: () -> Dp,
    hourHeightPx: Float,
    scrollState: ScrollState,
    viewportHeightPx: Int,
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
    return pointerInput(blockId, hourHeightPx, viewportHeightPx) {
        coroutineScope gestureScope@{
            awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val initial = latestBlock()
            val validator = latestValidator()
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
            val quickResizeDragThreshold = maxOf(
                touchSlop,
                QuickResizeDragThreshold.toPx()
            )
            var preHoldDrag = Offset.Zero
            var resizeBeforeHold = false
            var cancelledBeforeHold = false
            val tapped = withTimeoutOrNull(BlockMoveHoldMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        cancelledBeforeHold = true
                        return@withTimeoutOrNull false
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (change.isConsumed) {
                        cancelledBeforeHold = true
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
                    if (!potentialQuickResize && preHoldDrag.getDistance() > holdStillThreshold) {
                        cancelledBeforeHold = true
                        return@withTimeoutOrNull false
                    }
                }
            }

            if (tapped == false && !resizeBeforeHold) {
                cancelledBeforeHold = true
            }
            if (tapped == null && !resizeBeforeHold && !cancelledBeforeHold &&
                preHoldDrag.getDistance() > holdStillThreshold
            ) {
                cancelledBeforeHold = true
            }
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
                        validator = validator,
                        initialDy = preHoldDrag.y,
                        initialPointerY = downYInVisual,
                        hourHeightPx = hourHeightPx,
                        scrollState = scrollState,
                        viewportHeightPx = viewportHeightPx,
                        haptics = haptics,
                        onResizeActiveChange = onResizeActiveChange,
                        onResizePreview = onResizePreview,
                        onResize = onResize
                    )
                    return@awaitEachGesture
                }
                cancelledBeforeHold -> return@awaitEachGesture
            }

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onMoveActiveChange(true)

            val initialScroll = scrollState.value
            val initialBlockTopPx = initial.startMinutes / 60f * hourHeightPx
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
                    when (validator.placement(snapped, initial.durationMinutes)) {
                        MovePlacement.Invalid -> Unit
                        MovePlacement.TransientOnly -> {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        MovePlacement.Savable -> {
                            lastSavableStart = snapped
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            }

            try {
                updateMoveFromGesture()
                autoScrollJob = this@gestureScope.launchEdgeAutoScroll(
                    pointerViewportY = { pointerViewportY },
                    viewportHeightPx = viewportHeightPx,
                    scrollState = scrollState,
                    enabled = { hasDraggedAfterHold },
                    onScrolled = ::updateMoveFromGesture
                )

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        cancelled = true
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (!change.pressed) break
                    if (change.isConsumed) {
                        cancelled = true
                        break
                    }
                    totalPointerDy += change.position.y - change.previousPosition.y
                    pointerViewportY = initialPointerViewportY + totalPointerDy
                    if (!hasDraggedAfterHold && abs(totalPointerDy) > viewConfiguration.touchSlop) {
                        hasDraggedAfterHold = true
                    }
                    updateMoveFromGesture()
                    change.consume()
                }
            } catch (throwable: CancellationException) {
                cancelled = true
                throw throwable
            } finally {
                autoScrollJob?.cancel()
                val hasSavableDrop = lastSavableStart != initial.startMinutes
                if (!cancelled && (hasDraggedAfterHold || hasSavableDrop) && hasSavableDrop) {
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

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runResizeGesture(
    gestureScope: CoroutineScope,
    pointerId: PointerId,
    initial: PlannerBlock,
    validator: DayOccupancy,
    initialDy: Float,
    initialPointerY: Float,
    hourHeightPx: Float,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    haptics: HapticFeedback,
    onResizeActiveChange: (Boolean) -> Unit,
    onResizePreview: (Int?) -> Unit,
    onResize: (Int) -> Boolean
) {
    val initialScroll = scrollState.value
    val initialBlockTopPx = initial.startMinutes / 60f * hourHeightPx
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
            when (validator.placement(initial.startMinutes, snappedDuration)) {
                MovePlacement.Invalid -> Unit
                MovePlacement.TransientOnly -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                MovePlacement.Savable -> {
                    lastSavableDuration = snappedDuration
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    try {
        resizeTo()
        autoScrollJob = gestureScope.launchEdgeAutoScroll(
            pointerViewportY = { pointerViewportY },
            viewportHeightPx = viewportHeightPx,
            scrollState = scrollState,
            onScrolled = { resizeTo() }
        )

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.id != pointerId && it.pressed }) {
                cancelled = true
                break
            }
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null) {
                cancelled = true
                break
            }
            if (!change.pressed) break
            if (change.isConsumed) {
                cancelled = true
                break
            }
            totalDy += change.position.y - change.previousPosition.y
            pointerViewportY = initialPointerViewportY + totalDy
            resizeTo()
            change.consume()
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
