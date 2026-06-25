package com.privateplanner.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.DateLabelFormatter
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeOfDayColourMapper
import com.privateplanner.domain.TimeSnapper
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

private val HourHeight = HourHeightDp.dp
private val DayHeight = HourHeight * 24
private val ShortReadableFloor = ShortReadableFloorDp.dp
private val LongTitlePinMinHeight = LongTitlePinMinHeightDp.dp
private val TimelineTopClearance = 86.dp
private val TimelineHeaderHeight = 104.dp
private val TimelineGutter = 72.dp
private val MinimumTouchTarget = 48.dp
private val HourLabelHeight = 28.dp
private val HalfHourLabelHeight = 22.dp
private const val BlockMoveHoldMillis = 350L
private const val DayTransitionMillis = 220
private const val QuickResizeMaxDurationMinutes = 30
private const val ResizeLaneFraction = 0.24f
private val HoldStillTolerance = 14.dp
private val QuickResizeDragThreshold = 10.dp
private val ResizeHandleVisibleHeight = 42.dp
private val BlockAccentPalette = listOf(
    Color(0xFF596F3F),
    Color(0xFF9A5239),
    Color(0xFF386F89),
    Color(0xFF77518B),
    Color(0xFF806A2F),
    Color(0xFF70574E),
    Color(0xFF4F686B)
)

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
    val uiState by viewModel.uiState.collectAsState()
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
            message = "Deleted",
            actionLabel = "Undo",
            withDismissAction = false,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete(message.id)
        } else {
            viewModel.clearSnackbar(message.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(PlannerTestTags.Root)
            .background(PlannerColors.Paper)
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
                        onBlockMovePlacement = viewModel::movePlacement,
                        onBlockMove = viewModel::moveBlock,
                        onBlockCanResize = viewModel::canResizeBlock,
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
                        onDismiss = viewModel::dismissSheet
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
    Surface(
        color = PlannerColors.Sheet,
        contentColor = PlannerColors.PrimaryText,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 6.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = data.visuals.message,
                color = PlannerColors.PrimaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            val actionLabel = data.visuals.actionLabel
            if (actionLabel != null) {
                androidx.compose.material3.TextButton(onClick = data::performAction) {
                    Text(
                        text = actionLabel,
                        color = PlannerColors.Delete,
                        style = MaterialTheme.typography.labelLarge
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
                        0f to PlannerColors.Paper,
                        0.72f to PlannerColors.Paper,
                        0.9f to PlannerColors.Paper.copy(alpha = 0.72f),
                        1f to PlannerColors.Paper.copy(alpha = 0f)
                    )
                )
            )
    ) {
        // No chevrons (days change by swipe). The date is a compact centred block that SIZES ITSELF
        // to its content (no fixed height), tucked just under the status bar — so it can never be
        // clipped regardless of the status-bar/notch height. Smaller type frees calendar space; the
        // bottom padding gives the gradient a short fade so content stays readable under the top.
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
                    color = PlannerColors.MutedText,
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
    onBlockMovePlacement: (Long, Int) -> MovePlacement,
    onBlockMove: (Long, Int) -> Boolean,
    onBlockCanResize: (Long, Int, Int) -> Boolean,
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
    val renderItems = remember(blocks) {
        TilePolicy.renderItems(blocks)
    }
    var expandedShortOverlayIds by remember(selectedDate) { mutableStateOf<List<Long>?>(null) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }
    val currentTimeMinutes = remember { mutableIntStateOf(currentMinuteOfDay()) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val isToday = selectedDate == currentDate

    LaunchedEffect(Unit) {
        while (true) {
            currentDate = LocalDate.now()
            currentTimeMinutes.intValue = currentMinuteOfDay()
            delay(millisUntilNextMinute())
        }
    }

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
                .background(PlannerColors.Paper)
        ) {
            val timelineMaxWidth = maxWidth
            val timelineWidthPx = with(density) { maxWidth.toPx() }

            Box(
                modifier = Modifier
                    .offset(y = TimelineTopClearance)
                    .height(DayHeight)
                    .fillMaxWidth()
                    .background(PlannerColors.Paper)
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

                renderItems.forEach { item ->
                    when (item) {
                        is TileRenderItem.Single -> {
                            val block = item.block
                            val layout = layoutById[block.id] ?: BlockLayout(0, 1)
                            key(block.id) {
                                if (item.treatment == SingleTileTreatment.CompactChip) {
                                    ShortClusterTile(
                                        blocks = listOf(block),
                                        layout = layout,
                                        selected = selectedBlockId == block.id,
                                        timelineWidth = timelineMaxWidth,
                                        onTap = {
                                            expandedShortOverlayIds = listOf(block.id)
                                        }
                                    )
                                } else {
                                    TimeBlock(
                                        block = block,
                                        treatment = item.treatment,
                                        selected = selectedBlockId == block.id,
                                        layout = layout,
                                        timelineWidth = timelineMaxWidth,
                                        scrollState = scrollState,
                                        viewportHeightPx = viewportHeightPx,
                                        hourHeightPx = hourHeightPx,
                                        onTap = { onBlockTap(block.id) },
                                        onRename = { onBlockRename(block.id) },
                                        onDelete = { onBlockDelete(block.id) },
                                        onMovePlacement = { start -> onBlockMovePlacement(block.id, start) },
                                        onMove = { start -> onBlockMove(block.id, start) },
                                        onCanResize = { duration, maxOverlap ->
                                            onBlockCanResize(block.id, duration, maxOverlap)
                                        },
                                        onResize = { onBlockResize(block.id, it) }
                                    )
                                }
                            }
                        }
                        is TileRenderItem.DenseShortCluster -> {
                            val firstBlock = item.blocks.minWith(
                                PlannerBlockOrder
                            )
                            val layout = layoutById[firstBlock.id] ?: BlockLayout(0, 1)
                            key("cluster-${item.blocks.joinToString(separator = "-") { it.id.toString() }}") {
                                ShortClusterTile(
                                    blocks = item.blocks,
                                    layout = layout,
                                    selected = item.blocks.any { block -> block.id == selectedBlockId },
                                    timelineWidth = timelineMaxWidth,
                                    onTap = {
                                        expandedShortOverlayIds = item.blocks.map { block -> block.id }
                                    }
                                )
                            }
                        }
                    }
                }

                if (isToday) {
                    CurrentTimeIndicator(currentTimeMinutes)
                }

                val overlayBlocks = expandedShortOverlayIds
                    ?.mapNotNull { id -> blocks.firstOrNull { block -> block.id == id } }
                    ?.sortedWith(PlannerBlockOrder)
                    .orEmpty()
                if (overlayBlocks.isNotEmpty()) {
                    ShortTaskOverlay(
                        blocks = overlayBlocks,
                        timelineWidth = timelineMaxWidth,
                        onBlockTap = { id ->
                            expandedShortOverlayIds = null
                            onBlockTap(id)
                        },
                        modifier = Modifier.zIndex(7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineGrid(modifier: Modifier = Modifier) {
    val hourLine = PlannerColors.HourLine
    val halfHourLine = PlannerColors.HalfHourLine
    val quarterTick = PlannerColors.QuarterTick
    Box(
        modifier = modifier.drawWithCache {
            val hourHeight = HourHeight.toPx()
            val gutter = TimelineGutter.toPx()
            val fiveMinuteTickLength = 10.dp.toPx()
            val quarterTickLength = 18.dp.toPx()
            val strokePx = 1.dp.toPx()
            val hourPath = Path()
            val halfHourPath = Path()
            val fiveMinutePath = Path()
            val quarterPath = Path()

            for (hour in 0..24) {
                val y = (hour * hourHeight).roundToInt().toFloat()
                hourPath.line(gutter, y, size.width, y)
                if (hour < 24) {
                    for (tick in 1 until 12) {
                        if (tick % 3 != 0) {
                            val tickY = (y + hourHeight * tick / 12f).roundToInt().toFloat()
                            fiveMinutePath.line(gutter, tickY, gutter + fiveMinuteTickLength, tickY)
                        }
                    }
                    for (quarter in 1..3) {
                        val tickY = (y + hourHeight * quarter / 4f).roundToInt().toFloat()
                        if (quarter == 2) {
                            halfHourPath.line(gutter, tickY, size.width, tickY)
                        } else {
                            quarterPath.line(gutter, tickY, gutter + quarterTickLength, tickY)
                        }
                    }
                }
            }

            val fiveMinTickColor = quarterTick.copy(alpha = 0.72f)
            val stroke = Stroke(width = strokePx)
            onDrawBehind {
                drawPath(hourPath, hourLine, style = stroke)
                drawPath(halfHourPath, halfHourLine, style = stroke)
                drawPath(quarterPath, quarterTick, style = stroke)
                drawPath(fiveMinutePath, fiveMinTickColor, style = stroke)
            }
        }
    )
}

private fun Path.line(startX: Float, startY: Float, endX: Float, endY: Float) {
    moveTo(startX, startY)
    lineTo(endX, endY)
}

@Composable
private fun CurrentTimeIndicator(minutesState: IntState) {
    val minutes = minutesState.intValue
    val indicatorColor = PlannerColors.Delete
    val y = heightForMinutes(minutes)
    val labelTop = (y - HourLabelHeight / 2).coerceAtLeast(0.dp)
    val labelShape = RoundedCornerShape(7.dp)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(3f)
    ) {
        val yPx = (minutes / 60f * HourHeight.toPx()).roundToInt().toFloat()
        val gutter = TimelineGutter.toPx()
        drawLine(
            color = indicatorColor,
            start = Offset(gutter, yPx),
            end = Offset(size.width, yPx),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = indicatorColor,
            radius = 4.dp.toPx(),
            center = Offset(gutter, yPx)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(x = 6.dp, y = labelTop)
            .width(58.dp)
            .height(HourLabelHeight)
            .zIndex(4f)
            .clip(labelShape)
            .background(PlannerColors.Sheet)
            .border(1.dp, indicatorColor.copy(alpha = 0.35f), labelShape)
            .semantics {
                contentDescription = "Current time, ${TimeFormatter.time(minutes)}"
            }
    ) {
        Text(
            text = TimeFormatter.time(minutes),
            color = indicatorColor,
            fontFamily = DaytileFontFamily,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private fun currentMinuteOfDay(): Int = TimeSnapper.minuteOfDay(LocalTime.now())

private fun millisUntilNextMinute(): Long {
    val now = LocalTime.now()
    val millis = (60 - now.second) * 1_000L - now.nano / 1_000_000L
    return millis.coerceAtLeast(250L)
}

@Composable
private fun TimeLabels(modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 48)
    val hourLabels = remember { List(24) { hour -> TimeFormatter.time(hour * 60) } }
    val halfHourLabels = remember { List(24) { hour -> TimeFormatter.time(hour * 60 + 30) } }
    val hourStyle = remember {
        TextStyle(
            fontFamily = DaytileFontFamily,
            fontSize = 16.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    val halfHourStyle = remember {
        TextStyle(
            fontFamily = DaytileFontFamily,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    val hourLayouts = remember(textMeasurer, hourLabels, hourStyle) {
        hourLabels.map { label ->
            textMeasurer.measure(
                text = label,
                style = hourStyle,
                maxLines = 1
            )
        }
    }
    val halfHourLayouts = remember(textMeasurer, halfHourLabels, halfHourStyle) {
        halfHourLabels.map { label ->
            textMeasurer.measure(
                text = label,
                style = halfHourStyle,
                maxLines = 1
            )
        }
    }
    val hourColor = PlannerColors.TimeText
    val halfHourColor = PlannerColors.MutedText.copy(alpha = 0.9f)
    val labelWidth = TimelineGutter - 8.dp

    Canvas(modifier = modifier) {
        val labelWidthPx = labelWidth.toPx()
        val hourHeightPx = HourHeight.toPx()
        val hourLabelHeightPx = HourLabelHeight.toPx()
        val halfHourLabelHeightPx = HalfHourLabelHeight.toPx()
        drawTimelineLabels(
            layouts = hourLayouts,
            color = hourColor,
            labelWidthPx = labelWidthPx,
            labelHeightPx = hourLabelHeightPx
        ) { hour ->
            (hourHeightPx * hour - hourLabelHeightPx / 2f).coerceAtLeast(0f)
        }
        drawTimelineLabels(
            layouts = halfHourLayouts,
            color = halfHourColor,
            labelWidthPx = labelWidthPx,
            labelHeightPx = halfHourLabelHeightPx
        ) { hour ->
            hourHeightPx * hour + hourHeightPx / 2f - halfHourLabelHeightPx / 2f
        }
    }
}

private inline fun DrawScope.drawTimelineLabels(
    layouts: List<androidx.compose.ui.text.TextLayoutResult>,
    color: Color,
    labelWidthPx: Float,
    labelHeightPx: Float,
    topForHour: (Int) -> Float
) {
    layouts.forEachIndexed { hour, layout ->
        drawText(
            textLayoutResult = layout,
            color = color,
            topLeft = Offset(
                x = labelWidthPx - layout.size.width,
                y = topForHour(hour) + (labelHeightPx - layout.size.height) / 2f
            )
        )
    }
}

@Composable
private fun TimeBlock(
    block: PlannerBlock,
    treatment: SingleTileTreatment,
    selected: Boolean,
    layout: BlockLayout,
    timelineWidth: Dp,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    hourHeightPx: Float,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMovePlacement: (Int) -> MovePlacement,
    onMove: (Int) -> Boolean,
    onCanResize: (Int, Int) -> Boolean,
    onResize: (Int) -> Boolean
) {
    val haptics = LocalHapticFeedback.current
    val latestBlock by rememberUpdatedState(block)
    var previewStartMinutes by remember(block.id) { mutableStateOf<Int?>(null) }
    var previewDurationMinutes by remember(block.id) { mutableStateOf<Int?>(null) }
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(block.startMinutes, previewStartMinutes) {
        if (previewStartMinutes == block.startMinutes) {
            previewStartMinutes = null
        }
    }
    LaunchedEffect(block.durationMinutes, previewDurationMinutes) {
        if (previewDurationMinutes == block.durationMinutes) {
            previewDurationMinutes = null
        }
    }
    val displayedStartMinutes = previewStartMinutes ?: block.startMinutes
    val displayedDurationMinutes = previewDurationMinutes ?: block.durationMinutes

    val (left, width) = tileColumnMetrics(timelineWidth, layout)
    val baseTop = heightForMinutes(block.startMinutes)
    val baseTrueHeight = heightForMinutes(block.durationMinutes)
    val baseVisibleHeight = visibleTileHeight(baseTrueHeight, treatment)
    val baseTouchHeight = baseVisibleHeight.coerceAtLeast(MinimumTouchTarget)
    val touchTop = centeredTouchTop(baseTop, baseVisibleHeight)
    val snappedTop = heightForMinutes(displayedStartMinutes)
    val snappedTrueVisualHeight = heightForMinutes(displayedDurationMinutes)
    val trueVisualHeight = snappedTrueVisualHeight
    val visualHeight = visibleTileHeight(trueVisualHeight, treatment)
    val snappedVisualOffset = snappedTop - touchTop
    val visualOffset = snappedVisualOffset
    val touchHeight = maxOf(baseTouchHeight, visualOffset + visualHeight)
        .coerceAtMost((DayHeight - touchTop).coerceAtLeast(MinimumTouchTarget))
    val latestVisualOffset by rememberUpdatedState(visualOffset)
    val compact = displayedDurationMinutes <= QuickResizeMaxDurationMinutes
    // Colour is keyed to the tile's COMMITTED start time, not the live drag preview, so the tint
    // stays rock-steady while dragging (it only re-tints once, after you drop). Recomputing the
    // time-of-day colour on every snap was what made the whole tint flicker on each tiny move.
    val background = remember(block.startMinutes, layout.columnIndex) {
        Color(TimeOfDayColourMapper.backgroundArgb(block.startMinutes, layout.columnIndex))
    }
    val blockCornerRadius = if (compact) 13.dp else 16.dp
    val blockShape = RoundedCornerShape(blockCornerRadius)
    val accentColor = remember(block.id, compact) {
        blockAccentColor(block.id).copy(alpha = if (compact) 0.64f else 0.54f)
    }
    val resizeLaneWidth = (width * ResizeLaneFraction).coerceAtLeast(MinimumTouchTarget).coerceAtMost(width)
    val resizeTouchOffset = (visualOffset + visualHeight - MinimumTouchTarget)
        .coerceIn(0.dp, (touchHeight - MinimumTouchTarget).coerceAtLeast(0.dp))
    val rangeText = remember(displayedStartMinutes, displayedDurationMinutes) {
        TimeFormatter.range(displayedStartMinutes, displayedDurationMinutes)
    }
    val durationText = remember(displayedDurationMinutes) {
        TimeFormatter.duration(displayedDurationMinutes)
    }
    val showResizeHandle = visualHeight >= ResizeHandleVisibleHeight
    val movingGlass = lifted || previewStartMinutes != null
    val titleFollowOffset: Density.() -> Int = if (visualHeight < LongTitlePinMinHeight) {
        { 0 }
    } else {
        { titleFollowOffsetPx(scrollState.value, snappedTop, visualHeight) }
    }

    fun moveBy(deltaMinutes: Int): Boolean {
        val targetStart = TimeSnapper.clampStart(
            block.startMinutes + deltaMinutes,
            block.durationMinutes
        )
        if (targetStart == block.startMinutes) return false
        return onMovePlacement(targetStart) == MovePlacement.Savable && onMove(targetStart)
    }

    fun resizeBy(deltaMinutes: Int): Boolean {
        val targetDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            block.durationMinutes + deltaMinutes
        )
        if (targetDuration == block.durationMinutes) return false
        return onCanResize(targetDuration, OverlapPolicy.MaxSavedOverlap) && onResize(targetDuration)
    }

    Box(
        modifier = Modifier
            .offset(x = left, y = touchTop)
            .width(width)
            .height(touchHeight)
            .testTag(PlannerTestTags.TimeBlock)
            .zIndex(if (lifted) 2f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = "${block.title}, ${TimeFormatter.spokenRange(block.startMinutes, block.durationMinutes)}, $durationText. Actions: Rename, Delete."
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
                latestVisualOffset = { latestVisualOffset },
                hourHeightPx = hourHeightPx,
                scrollState = scrollState,
                viewportHeightPx = viewportHeightPx,
                haptics = haptics,
                onTap = onTap,
                onLiftedChange = { lifted = it },
                onMovePreview = { previewStartMinutes = it },
                onMovePlacement = onMovePlacement,
                onMove = onMove,
                onResizePreview = { previewDurationMinutes = it },
                onCanResize = onCanResize,
                onResize = onResize
            )
    ) {
        Box(
            modifier = Modifier
                .offset(y = visualOffset)
                .fillMaxWidth()
                .height(visualHeight)
                .liftedGlassLayer(blockShape, lifted)
                .clip(blockShape)
        ) {
            // The dragged tile draws NO backdrop of its own — no GPU lens, no re-rendered copies of
            // the tiles behind. Those copies (faded or not) were what left the faint inner rectangle
            // and cut off the task underneath. It is now only a clean tinted glass pane painted with
            // full-surface gradients + a thin edge lip (TimeBlockForeground); the real tiles behind
            // show straight through it at their own full size. Rested tiles keep the grid backdrop.
            if (!movingGlass) {
                TimelineGlassBackdrop(
                    timelineWidth = timelineWidth,
                    tileLeft = left,
                    tileTop = snappedTop,
                    shape = blockShape,
                    compact = compact,
                    lifted = false
                )
            }

            TimeBlockForeground(
                background = background,
                blockCornerRadius = blockCornerRadius,
                compact = compact,
                movingGlass = movingGlass,
                selected = selected,
                accentColor = accentColor,
                trueVisualHeight = trueVisualHeight,
                treatment = treatment,
                title = block.title,
                rangeText = rangeText,
                durationText = durationText,
                visualHeight = visualHeight,
                titleFollowOffset = titleFollowOffset,
                showResizeHandle = showResizeHandle
            )
        }

        if (showResizeHandle) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = resizeTouchOffset)
                    .width(resizeLaneWidth)
                    .height(MinimumTouchTarget)
                    .semantics {
                        contentDescription = "Resize ${block.title}"
                    }
            )
        }
    }
}

@Composable
private fun BoxScope.TimeBlockForeground(
    background: Color,
    blockCornerRadius: Dp,
    compact: Boolean,
    movingGlass: Boolean,
    selected: Boolean,
    accentColor: Color,
    trueVisualHeight: Dp,
    treatment: SingleTileTreatment,
    title: String,
    rangeText: String,
    durationText: String,
    visualHeight: Dp,
    titleFollowOffset: Density.() -> Int,
    showResizeHandle: Boolean
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
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .height(spineHeight(trueVisualHeight, treatment))
                .width(if (compact) 3.dp else 4.dp)
                .background(accentColor)
        )

        BlockContent(
            title = title,
            rangeText = rangeText,
            durationText = durationText,
            height = visualHeight,
            titleFollowOffset = titleFollowOffset
        )

        if (showResizeHandle) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PlannerColors.PrimaryText.copy(alpha = 0.18f))
            )
        }
    }
}

@Composable
private fun ShortClusterTile(
    blocks: List<PlannerBlock>,
    layout: BlockLayout,
    selected: Boolean,
    timelineWidth: Dp,
    onTap: () -> Unit
) {
    val firstBlock = blocks.minWith(PlannerBlockOrder)
    val startMinutes = blocks.minOf { it.startMinutes }
    val endMinutes = blocks.maxOf { it.endMinutes }
    val trueHeight = heightForMinutes(endMinutes - startMinutes)
    val touchHeight = trueHeight.coerceAtLeast(MinimumTouchTarget)
    val top = heightForMinutes(startMinutes)
    val touchTop = centeredTouchTop(top, trueHeight)
    val visualOffset = top - touchTop
    val (left, width) = tileColumnMetrics(timelineWidth, layout)
    val shape = RoundedCornerShape(12.dp)
    val background = Color(TimeOfDayColourMapper.backgroundArgb(startMinutes, layout.columnIndex))
    val accentColor = blockAccentColor(firstBlock.id).copy(alpha = 0.62f)
    val title = if (blocks.size == 1) {
        firstBlock.title
    } else {
        "${blocks.size} tasks"
    }

    Box(
        modifier = Modifier
            .offset(x = left, y = touchTop)
            .width(width)
            .height(touchHeight)
            .testTag(PlannerTestTags.ShortCluster)
            .zIndex(0.9f)
            .semantics {
                contentDescription = "$title, ${TimeFormatter.time(startMinutes)} to ${TimeFormatter.time(endMinutes)}"
                onClick(label = "Open short tasks") {
                    onTap()
                    true
                }
            }
            .clickable(onClick = onTap)
    ) {
        Box(
            modifier = Modifier
                .offset(y = visualOffset)
                .fillMaxWidth()
                .height(trueHeight)
                .liftedGlassLayer(shape, lifted = false)
                .clip(shape)
                .liquidGlassTileSurface(
                    tint = background,
                    shade = PlannerColors.GlassShade,
                    cornerRadius = 12.dp,
                    compact = true,
                    selectionColor = if (selected) {
                        PlannerColors.PrimaryText.copy(alpha = 0.52f)
                    } else {
                        Color.Transparent
                    }
                )
        ) {
            TimelineGlassBackdrop(
                timelineWidth = timelineWidth,
                tileLeft = left,
                tileTop = top,
                shape = shape,
                compact = true
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(4.dp)
                    .height(trueHeight.coerceAtLeast(TrueDurationMarkerMinDp.dp))
                    .background(accentColor)
            )
            if (trueHeight >= 14.dp) {
                Text(
                    text = title,
                    color = PlannerColors.PrimaryText,
                    fontFamily = DaytileFontFamily,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp, end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ShortTaskOverlay(
    blocks: List<PlannerBlock>,
    timelineWidth: Dp,
    onBlockTap: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val startMinutes = blocks.minOf { it.startMinutes }
    val overlayWidth = (timelineWidth - TimelineGutter - 20.dp).coerceAtLeast(180.dp)
    val overlayTop = (heightForMinutes(startMinutes))
        .coerceIn(0.dp, (DayHeight - ShortReadableFloor * blocks.size.toFloat()).coerceAtLeast(0.dp))
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .offset(x = TimelineGutter + 10.dp, y = overlayTop)
            .width(overlayWidth)
            .liftedGlassLayer(shape, lifted = true)
            .clip(shape)
            .liquidGlassTileSurface(
                tint = PlannerColors.Sheet,
                shade = PlannerColors.GlassShade,
                cornerRadius = 12.dp,
                compact = false
            )
            .testTag(PlannerTestTags.ShortTaskOverlay)
    ) {
        TimelineGlassBackdrop(
            timelineWidth = timelineWidth,
            tileLeft = TimelineGutter + 10.dp,
            tileTop = overlayTop,
            shape = shape,
            compact = false,
            lifted = true
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            blocks.forEach { block ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ShortReadableFloor)
                        .clickable { onBlockTap(block.id) }
                        .padding(start = 10.dp, end = 8.dp)
                        .semantics {
                            contentDescription = "${block.title}, ${TimeFormatter.spokenRange(block.startMinutes, block.durationMinutes)}"
                        }
                ) {
                    Text(
                        text = block.title,
                        color = PlannerColors.PrimaryText,
                        fontFamily = DaytileFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${TimeFormatter.time(block.startMinutes)} ${TimeFormatter.duration(block.durationMinutes)}",
                        color = PlannerColors.MutedText,
                        fontFamily = DaytileFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

private data class TileColumnMetrics(val left: Dp, val width: Dp)

private fun heightForMinutes(minutes: Int): Dp = HourHeight * (minutes / 60f)

private fun centeredTouchTop(top: Dp, contentHeight: Dp): Dp {
    val touchHeight = contentHeight.coerceAtLeast(MinimumTouchTarget)
    return (top - (touchHeight - contentHeight) / 2)
        .coerceIn(0.dp, (DayHeight - touchHeight).coerceAtLeast(0.dp))
}

private fun tileColumnMetrics(timelineWidth: Dp, layout: BlockLayout): TileColumnMetrics {
    val columnWidth = (timelineWidth - TimelineGutter - 10.dp).coerceAtLeast(1.dp) /
        layout.columnCount.coerceAtLeast(1)
    val left = TimelineGutter + columnWidth * layout.columnIndex
    val width = (columnWidth - 4.dp).coerceAtLeast(MinimumTouchTarget)
    return TileColumnMetrics(left, width)
}

private fun visibleTileHeight(
    trueHeight: Dp,
    treatment: SingleTileTreatment
): Dp {
    return when (treatment) {
        SingleTileTreatment.ReadableFloor -> trueHeight.coerceAtLeast(ShortReadableFloor)
        SingleTileTreatment.Normal,
        SingleTileTreatment.CompactChip -> trueHeight
    }
}

private fun spineHeight(
    trueHeight: Dp,
    treatment: SingleTileTreatment
): Dp {
    return when (treatment) {
        SingleTileTreatment.ReadableFloor -> trueHeight.coerceAtLeast(TrueDurationMarkerMinDp.dp)
        SingleTileTreatment.Normal,
        SingleTileTreatment.CompactChip -> trueHeight
    }
}

private fun Density.titleFollowOffsetPx(
    scrollPx: Int,
    blockTop: Dp,
    visualHeight: Dp
): Int {
    val desiredTitleTop = scrollPx + (TimelineHeaderHeight + 6.dp - TimelineTopClearance - blockTop).toPx()
    val normalTitleTop = 8.dp.toPx()
    val maxOffset = (visualHeight - 56.dp).coerceAtLeast(0.dp).toPx()
    return (desiredTitleTop - normalTitleTop).coerceIn(0f, maxOffset).roundToInt()
}

private fun Modifier.liftedGlassLayer(
    shape: RoundedCornerShape,
    lifted: Boolean
): Modifier {
    return graphicsLayer {
        scaleX = if (lifted) 1.025f else 1f
        scaleY = if (lifted) 1.025f else 1f
        // A translucent tile shows its OWN elevation shadow THROUGH itself (the shadow pools toward the
        // bottom) — that was the faint "rectangle" that appeared on every layer. So a lifted glass tile
        // casts no elevation shadow; the lift is read from the slight scale-up and the bright edge rim.
        shadowElevation = if (lifted) 0f else 4.dp.toPx()
        this.shape = shape
        clip = false
        ambientShadowColor = Color.Black.copy(alpha = if (lifted) 0f else 0.055f)
        spotShadowColor = Color.Black.copy(alpha = if (lifted) 0f else 0.040f)
    }
}

@Composable
private fun BoxScope.TimelineGlassBackdrop(
    timelineWidth: Dp,
    tileLeft: Dp,
    tileTop: Dp,
    shape: RoundedCornerShape,
    compact: Boolean,
    lifted: Boolean = false
) {
    val density = LocalDensity.current
    val paper = PlannerColors.Paper
    val hourLine = PlannerColors.HourLine
    val halfHourLine = PlannerColors.HalfHourLine
    val quarterTick = PlannerColors.QuarterTick
    var tileSize by remember { mutableStateOf(IntSize.Zero) }
    val blurRadiusPx = with(density) {
        when {
            compact -> 2.8.dp
            lifted -> 10.dp
            else -> 4.8.dp
        }.toPx()
    }
    val edgeDepthPx = with(density) {
        when {
            compact -> 13.dp
            lifted -> 36.dp
            else -> 27.dp
        }.toPx()
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
        lifted -> 0.62f
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
                val gridPaths = timelineGridCropPaths(
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
                    if (!lifted) {
                        drawRect(paper)
                    }
                    drawPath(gridPaths.hour, hourLine, style = stroke)
                    drawPath(gridPaths.halfHour, halfHourLine, style = stroke)
                    drawPath(gridPaths.quarter, quarterTick, style = stroke)
                    drawPath(gridPaths.fiveMinute, fiveMinTick, style = stroke)
                }
            }
    )
}

private data class TimelineGridCropPaths(
    val hour: Path,
    val halfHour: Path,
    val quarter: Path,
    val fiveMinute: Path
)

private fun timelineGridCropPaths(
    tileLeftPx: Float,
    tileTopPx: Float,
    tileHeightPx: Float,
    timelineWidthPx: Float,
    hourHeightPx: Float,
    gutterPx: Float,
    fiveMinuteTickLengthPx: Float,
    quarterTickLengthPx: Float,
    strokePx: Float
): TimelineGridCropPaths {
    val hourPath = Path()
    val halfHourPath = Path()
    val quarterPath = Path()
    val fiveMinutePath = Path()
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

    return TimelineGridCropPaths(
        hour = hourPath,
        halfHour = halfHourPath,
        quarter = quarterPath,
        fiveMinute = fiveMinutePath
    )
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

    SideEffect {
        shader.setFloatUniform("size", widthPx, heightPx)
        shader.setFloatUniform("edgeDepth", edgeDepthPx)
        shader.setFloatUniform("cornerRadius", cornerRadiusPx)
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

private fun Modifier.liquidGlassTileSurface(
    tint: Color,
    shade: Color,
    cornerRadius: Dp,
    compact: Boolean,
    lifted: Boolean = false,
    selectionColor: Color = Color.Transparent
): Modifier {
    return drawWithCache {
        val radiusPx = cornerRadius.toPx()
        val corner = CornerRadius(radiusPx, radiusPx)
        val restedTint = lerp(tint, shade, 0.06f)
        val glassTint = if (lifted) {
            lerp(tint, Color.White, 0.16f)
        } else {
            lerp(restedTint, Color.White, 0.025f)
        }
        // While lifted, the tint is a translucent wash over the whole rounded shape: enough to read
        // clearly as a tinted pane of glass with its own crisp text, but translucent so the tiles
        // behind it stay visible at their OWN full size and colour through the glass. Nothing is
        // redrawn behind it, so underlying tasks are never shrunk or stripped of their text.
        val bodyAlpha = when {
            lifted -> 0.52f
            compact -> 0.90f
            else -> 0.94f
        }
        val milkAlpha = when {
            lifted -> 0.03f
            compact -> 0.014f
            else -> 0.018f
        }
        // ONE continuous top-to-bottom wash for EVERY tile (brighter when lifted, subtler at rest):
        // a bright crown fading to a deeper foot, with NO transparent gap and NO pass that stops
        // short of an edge. Because every pixel is covered evenly there is no plain centre framed by
        // where edge highlights die — and that framed centre, showing through a dragged tile, was the
        // "rectangle". This applies to rested tiles too (the one behind the dragged tile), which is
        // why the rectangle persisted before. A lifted tile adds a thin bright lip on the edge.
        val luminanceBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (lifted) 0.38f else 0.07f),
                Color.White.copy(alpha = if (lifted) 0.10f else 0.02f),
                shade.copy(alpha = if (lifted) 0.07f else 0.04f),
                shade.copy(alpha = if (lifted) 0.22f else 0.09f)
            ),
            startY = 0f,
            endY = size.height
        )
        val liftedRimBrush = if (lifted) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.62f),
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.08f)
                ),
                startY = 0f,
                endY = size.height
            )
        } else {
            null
        }
        onDrawWithContent {
            drawRoundRect(
                color = glassTint.copy(alpha = bodyAlpha),
                cornerRadius = corner
            )
            if (milkAlpha > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = milkAlpha),
                    cornerRadius = corner
                )
            }
            drawRoundRect(brush = luminanceBrush, cornerRadius = corner)
            if (selectionColor.alpha > 0f) {
                drawRoundRect(
                    color = selectionColor.copy(alpha = selectionColor.alpha * if (lifted) 0.12f else 0.16f),
                    cornerRadius = corner
                )
            }
            drawContent()
            liftedRimBrush?.let {
                drawRoundRect(
                    brush = it,
                    cornerRadius = corner,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
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
    height: Dp,
    titleFollowOffset: Density.() -> Int = { 0 }
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = height < 48.dp
        val durationStyle = remember(height, compact) {
            durationLabelStyle(height, compact)
        }
        val durationDecision = remember(maxWidth, durationText, compact, durationStyle.fontSize) {
            TilePolicy.durationDisplayDecision(
                tileWidthDp = maxWidth.value,
                durationText = durationText,
                compact = compact,
                durationFontSizeSp = durationStyle.fontSize.value
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
                style = durationStyle,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(durationReserve)
                    .padding(end = durationStyle.endPadding)
            )
        }
    }
}

@Composable
private fun BlockOneLineContent(
    title: String,
    titleFontSize: androidx.compose.ui.unit.TextUnit,
    titleLineHeight: androidx.compose.ui.unit.TextUnit,
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
    titleFontSize: androidx.compose.ui.unit.TextUnit,
    titleLineHeight: androidx.compose.ui.unit.TextUnit,
    metaFontSize: androidx.compose.ui.unit.TextUnit,
    metaLineHeight: androidx.compose.ui.unit.TextUnit,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = rangeText,
                color = PlannerColors.PrimaryText.copy(alpha = 0.72f),
                fontFamily = DaytileFontFamily,
                fontSize = metaFontSize,
                lineHeight = metaLineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DurationLabel(
    text: String,
    style: DurationLabelStyle,
    modifier: Modifier
) {
    Text(
        text = text,
        color = PlannerColors.PrimaryText.copy(alpha = style.textAlpha),
        fontFamily = DaytileFontFamily,
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        fontWeight = style.fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
    )
}

private data class DurationLabelStyle(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val endPadding: Dp,
    val textAlpha: Float,
    val fontWeight: FontWeight
)

private fun durationLabelStyle(height: Dp, compact: Boolean): DurationLabelStyle {
    val fontSize = when {
        height < 32.dp -> 11f
        height < 64.dp -> 12f
        height < 128.dp -> 13f
        else -> 14f
    }
    return DurationLabelStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 2f).sp,
        endPadding = if (compact) 5.dp else 8.dp,
        textAlpha = if (compact) 0.80f else 0.86f,
        fontWeight = if (height >= 64.dp) FontWeight.Bold else FontWeight.SemiBold
    )
}

private fun blockAccentColor(blockId: Long): Color {
    val hash = (blockId xor (blockId ushr 32)).toInt() and Int.MAX_VALUE
    return BlockAccentPalette[hash % BlockAccentPalette.size]
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
            val hitBlock = hitTestBlock(
                offset = offset,
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

private fun hitTestBlock(
    offset: Offset,
    blocks: List<PlannerBlock>,
    layoutById: Map<Long, BlockLayout>,
    timelineWidthPx: Float,
    gutterPx: Float,
    hourHeightPx: Float,
    minimumTouchTargetPx: Float
): Boolean {
    return TimelineGeometry.hitTestBlock(
        x = offset.x,
        y = offset.y,
        blocks = blocks,
        layoutById = layoutById,
        timelineWidthPx = timelineWidthPx,
        gutterPx = gutterPx,
        hourHeightPx = hourHeightPx,
        minimumTouchTargetPx = minimumTouchTargetPx
    )
}

private fun Modifier.blockMoveInput(
    blockId: Long,
    latestBlock: () -> PlannerBlock,
    latestVisualOffset: () -> Dp,
    hourHeightPx: Float,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    haptics: HapticFeedback,
    onTap: () -> Unit,
    onLiftedChange: (Boolean) -> Unit,
    onMovePreview: (Int?) -> Unit,
    onMovePlacement: (Int) -> MovePlacement,
    onMove: (Int) -> Boolean,
    onResizePreview: (Int?) -> Unit,
    onCanResize: (Int, Int) -> Boolean,
    onResize: (Int) -> Boolean
): Modifier {
    return pointerInput(blockId, hourHeightPx, viewportHeightPx) {
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
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
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

            when {
                tapped == true -> {
                    onTap()
                    return@awaitEachGesture
                }
                resizeBeforeHold -> {
                    runResizeGesture(
                        pointerId = down.id,
                        initial = initial,
                        initialDy = preHoldDrag.y,
                        initialPointerY = downYInVisual,
                        hourHeightPx = hourHeightPx,
                        scrollState = scrollState,
                        viewportHeightPx = viewportHeightPx,
                        haptics = haptics,
                        onResizePreview = onResizePreview,
                        onCanResize = onCanResize,
                        onResize = onResize
                    )
                    return@awaitEachGesture
                }
                cancelledBeforeHold -> return@awaitEachGesture
            }

            onLiftedChange(true)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)

            val initialScroll = scrollState.value
            val initialBlockTopPx = initial.startMinutes / 60f * hourHeightPx
            val initialPointerViewportY = initialBlockTopPx - initialScroll + downYInVisual
            var totalPointerDy = preHoldDrag.y
            var pointerViewportY = initialPointerViewportY + totalPointerDy
            var lastStart = initial.startMinutes
            var lastSavedStart = initial.startMinutes
            var hasDraggedAfterHold = false
            try {
                fun updateMoveFromGesture() {
                    val effectiveDy = totalPointerDy + (scrollState.value - initialScroll)
                    val snapped = TimeSnapper.clampStart(
                        initial.startMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourHeightPx),
                        initial.durationMinutes
                    )
                    if (snapped != lastStart) {
                        when (onMovePlacement(snapped)) {
                            MovePlacement.Invalid -> Unit
                            MovePlacement.TransientOnly -> {
                                lastStart = snapped
                                onMovePreview(snapped)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            MovePlacement.Savable -> {
                                lastStart = snapped
                                lastSavedStart = snapped
                                onMovePreview(snapped)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                }

                updateMoveFromGesture()

                while (true) {
                    val event = withTimeoutOrNull(AutoScrollFrameMillis) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }

                    if (event == null) {
                        if (hasDraggedAfterHold) {
                            applyEdgeAutoScroll(
                                pointerViewportY = pointerViewportY,
                                viewportHeightPx = viewportHeightPx,
                                scrollState = scrollState,
                                onScrolled = ::updateMoveFromGesture
                            )
                        }
                        continue
                    }

                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.isConsumed) break
                    val delta = change.positionChange()
                    totalPointerDy += delta.y
                    pointerViewportY = initialPointerViewportY + totalPointerDy
                    if (abs(totalPointerDy) > viewConfiguration.touchSlop) {
                        hasDraggedAfterHold = true
                    }
                    if (hasDraggedAfterHold) {
                        applyEdgeAutoScroll(
                            pointerViewportY = pointerViewportY,
                            viewportHeightPx = viewportHeightPx,
                            scrollState = scrollState,
                            onScrolled = ::updateMoveFromGesture
                        )
                    }
                    updateMoveFromGesture()
                    change.consume()
                }
            } finally {
                var committed = false
                if (hasDraggedAfterHold) {
                    if (lastStart != lastSavedStart) {
                        onMovePreview(lastSavedStart)
                    }
                    if (lastSavedStart != initial.startMinutes) {
                        committed = onMove(lastSavedStart)
                    }
                }
                if (!committed) {
                    onMovePreview(null)
                }
                onLiftedChange(false)
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runResizeGesture(
    pointerId: PointerId,
    initial: PlannerBlock,
    initialDy: Float,
    initialPointerY: Float,
    hourHeightPx: Float,
    scrollState: ScrollState,
    viewportHeightPx: Int,
    haptics: HapticFeedback,
    onResizePreview: (Int?) -> Unit,
    onCanResize: (Int, Int) -> Boolean,
    onResize: (Int) -> Boolean
) {
    val initialScroll = scrollState.value
    val initialBlockTopPx = initial.startMinutes / 60f * hourHeightPx
    val initialPointerViewportY = initialBlockTopPx - initialScroll + initialPointerY
    var totalDy = initialDy
    var pointerViewportY = initialPointerViewportY + totalDy
    var lastDuration = initial.durationMinutes

    fun resizeTo(): Boolean {
        val effectiveDy = totalDy + (scrollState.value - initialScroll)
        val snappedDuration = TimeSnapper.clampDuration(
            initial.startMinutes,
            initial.durationMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourHeightPx)
        )
        if (snappedDuration != lastDuration &&
            onCanResize(snappedDuration, OverlapPolicy.MaxTransientOverlap)
        ) {
            lastDuration = snappedDuration
            onResizePreview(snappedDuration)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return true
        }
        return false
    }

    try {
        resizeTo()

        while (true) {
            val event = withTimeoutOrNull(AutoScrollFrameMillis) {
                awaitPointerEvent(PointerEventPass.Initial)
            }

            if (event == null) {
                applyEdgeAutoScroll(
                    pointerViewportY = pointerViewportY,
                    viewportHeightPx = viewportHeightPx,
                    scrollState = scrollState,
                    onScrolled = { resizeTo() }
                )
                continue
            }

            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) break
            totalDy += change.positionChange().y
            pointerViewportY = initialPointerViewportY + totalDy
            applyEdgeAutoScroll(
                pointerViewportY = pointerViewportY,
                viewportHeightPx = viewportHeightPx,
                scrollState = scrollState,
                onScrolled = { resizeTo() }
            )
            resizeTo()
            change.consume()
        }
    } finally {
        if (lastDuration != initial.durationMinutes) {
            onResize(lastDuration)
        }
        onResizePreview(null)
    }
}

private const val AutoScrollFrameMillis = 16L

private fun applyEdgeAutoScroll(
    pointerViewportY: Float,
    viewportHeightPx: Int,
    scrollState: ScrollState,
    onScrolled: () -> Unit
) {
    val scrollDelta = edgeAutoScrollDelta(pointerViewportY, viewportHeightPx)
    if (scrollDelta != 0f) {
        scrollState.dispatchRawDelta(scrollDelta)
        onScrolled()
    }
}

private fun edgeAutoScrollDelta(
    pointerViewportY: Float,
    viewportHeightPx: Int
): Float {
    return TimelineGeometry.edgeAutoScrollDelta(
        pointerViewportY = pointerViewportY,
        viewportHeightPx = viewportHeightPx,
        topEdgePx = 112f,
        bottomEdgeMinPx = 240f,
        bottomEdgeFraction = 0.30f,
        topMaxStepPx = 16f,
        bottomMaxStepPx = 42f
    )
}

private fun Modifier.axisLockedDaySwipe(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled, onPrevious, onNext) {
        val swipeThreshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var horizontal = false
            var vertical = false

            while (true) {
                val event = awaitPointerEvent()
                val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                total += delta

                if (!horizontal && !vertical && total.getDistance() > touchSlop) {
                    if (abs(total.x) > abs(total.y)) {
                        horizontal = true
                    } else {
                        vertical = true
                    }
                }

                if (vertical) {
                    return@awaitEachGesture
                }

                if (horizontal) {
                    change.consume()
                }
            }

            if (horizontal && abs(total.x) >= swipeThreshold) {
                if (total.x > 0f) onPrevious() else onNext()
            }
        }
    }
}
