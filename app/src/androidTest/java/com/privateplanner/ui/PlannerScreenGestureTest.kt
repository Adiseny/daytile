package com.privateplanner.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.data.PlannerBlockEntity
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerScreenGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var database: PlannerDatabase? = null
    private val recordedHaptics = mutableListOf<HapticFeedbackType>()
    private val hapticFeedback = RecordingHapticFeedback(recordedHaptics)

    @After
    fun closeDatabase() {
        compose.runOnUiThread { compose.activity.setContent { } }
        compose.waitForIdle()
        database?.close()
        database = null
    }

    @Test
    fun tapEmptyTimeCreatesBlockAndOpensActions() {
        setPlannerContent()

        compose.onRoot()
            .performTouchInput {
                down(center)
                up()
            }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextInput("Focus")
        compose.onNodeWithText("Add").performClick()

        compose.waitUntilNodeWithText("Focus")
        compose.onNodeWithText("Focus").performClick()
        compose.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun timelineAccessibilityActionOpensCreateSheet() {
        setPlannerContent()

        compose.onNode(hasContentDescription("Day timeline", substring = true))
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun horizontalSwipeChangesDayAndBack() {
        setPlannerContent()

        compose.onNodeWithText("Today").assertExists()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntilNodeWithText("Tomorrow")
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitUntilNodeWithText("Today")
        assertEquals(
            listOf(HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentTick),
            recordedHaptics
        )
    }

    @Test
    fun blockDragResizeAndAccessibilityActionsRemainStable() {
        setPlannerContent {
            insertVisibleBlock(title = "Move me")
        }

        compose.waitUntilBlockExists("Move me")
        val original = storedBlock("Move me")
        compose.onNode(hasContentDescription("Move me", substring = true)).performTouchInput {
            down(center)
            advanceEventTime(450)
            moveBy(Offset(0f, 80f))
            up()
        }

        val moved = waitUntilStoredBlock("Move me") { block ->
            block.startMinutes != original.startMinutes
        }
        compose.onNode(hasContentDescription("Move me", substring = true)).performTouchInput {
            down(Offset(centerX, bottom - 4f))
            moveBy(Offset(0f, 80f))
            up()
        }

        val resized = waitUntilStoredBlock("Move me") { block ->
            block.durationMinutes != moved.durationMinutes
        }
        val actions = compose.onNode(hasContentDescription("Move me", substring = true))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertTrue(actions.first { it.label == "Lengthen 5 minutes" }.action())
        val lengthened = waitUntilStoredBlock("Move me") { block ->
            block.durationMinutes > resized.durationMinutes
        }
        assertEquals(resized.durationMinutes + TimeSnapper.SnapMinutes, lengthened.durationMinutes)
    }

    @Test
    fun crowdedTimelineRenders() {
        setPlannerContent {
            seedCrowdedVisibleDay()
        }
        compose.waitUntilBlockExists(title = "Load", timeoutMillis = 5_000)
    }

    @Test
    fun largeFontCreateAndActionFlowRemainReachable() {
        setPlannerContent(fontScale = 2f)

        compose.onRoot()
            .performTouchInput {
                down(center)
                up()
            }
        compose.onNode(hasSetTextAction()).performTextInput("Large font task")
        compose.onNodeWithText("Add").performClick()

        compose.waitUntilNodeWithText("Large font task")
        compose.onNodeWithText("Large font task").performClick()
        compose.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun createTitleInputCapsLongTitleBeforeSaving() {
        val cappedTitle = "A".repeat(MaxTitleLength)
        setPlannerContent()

        compose.onRoot()
            .performTouchInput {
                down(center)
                up()
            }
        val titleInput = compose.onNode(hasSetTextAction())
        titleInput.performTextInput(cappedTitle + "overflow")

        assertEquals(
            cappedTitle,
            titleInput.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )
        val beforeEmoji = "A".repeat(MaxTitleLength - 1)
        titleInput.performTextReplacement(beforeEmoji + "\uD83D\uDE00overflow")
        assertEquals(
            beforeEmoji,
            titleInput.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )
        titleInput.performTextReplacement(cappedTitle)
        compose.onNodeWithText("Add").performClick()
        compose.waitUntilBlockExists(cappedTitle)
    }

    @Test
    fun completePlanningWorkflowRemainsConsistent() {
        val viewModel = setPlannerContent()
        val today = viewModel.uiState.value.selectedDate

        repeat(7) { index ->
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitUntil {
                viewModel.uiState.value.selectedDate == today.plusDays(index + 1L)
            }
        }
        repeat(7) { index ->
            compose.onRoot().performTouchInput { swipeRight() }
            compose.waitUntil {
                viewModel.uiState.value.selectedDate == today.plusDays(6L - index)
            }
        }

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Cancel").performClick()

        val timeline = hasContentDescription("Day timeline", substring = true)
        val initialTimelineDescription = compose.onNode(timeline)
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitUntil {
            compose.onNode(timeline).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription] != initialTimelineDescription
        }
        compose.onRoot().performTouchInput { swipeDown() }

        compose.onNode(timeline).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).performTextInput("Workflow task")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntilBlockExists("Workflow task")

        compose.onNode(hasContentDescription("Workflow task", substring = true)).performClick()
        compose.onNode(hasContentDescription("Rename Workflow task")).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed workflow")
        compose.onNodeWithText("Rename").performClick()
        compose.waitUntilBlockExists("Renamed workflow")

        compose.onNode(hasContentDescription("Renamed workflow", substring = true)).performClick()
        compose.onNodeWithText("Delete").performClick()
        waitUntilStoredBlockMissing("Renamed workflow")
        compose.waitUntilNodeWithText("Undo")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntilBlockExists("Renamed workflow")

        compose.onNode(hasContentDescription("Renamed workflow", substring = true)).performClick()
        compose.onNodeWithText("Delete").performClick()
        waitUntilStoredBlockMissing("Renamed workflow")
    }

    private fun setPlannerContent(
        fontScale: Float = 1f,
        seed: suspend PlannerDatabase.() -> Unit = {}
    ): PlannerViewModel {
        val db = Room.inMemoryDatabaseBuilder(
            compose.activity.applicationContext,
            PlannerDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        database = db
        runBlocking { db.seed() }

        val viewModel = PlannerViewModel(PlannerRepository(db))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
                LocalHapticFeedback provides hapticFeedback
            ) {
                PlannerTheme {
                    PlannerScreen(viewModel = viewModel)
                }
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private suspend fun PlannerDatabase.insertVisibleBlock(title: String) {
        insertBlock(
            title = title,
            startMinutes = visibleStartMinutes(),
            durationMinutes = TimeSnapper.DefaultDurationMinutes
        )
    }

    private suspend fun PlannerDatabase.insertBlock(
        title: String,
        startMinutes: Int,
        durationMinutes: Int
    ) {
        blockDao().insertBlock(
            PlannerBlockEntity(
                dateEpochDay = LocalDate.now().toEpochDay(),
                title = title,
                startMinutes = startMinutes,
                durationMinutes = durationMinutes
            )
        )
    }

    private suspend fun PlannerDatabase.seedCrowdedVisibleDay() {
        val today = LocalDate.now().toEpochDay()
        val firstStart = (visibleStartMinutes() - TimeSnapper.MinutesPerHour)
            .coerceAtLeast(0)
        var id = 1L
        for (start in firstStart until (firstStart + 4 * TimeSnapper.MinutesPerHour)
            .coerceAtMost(TimeSnapper.MinutesPerDay - TimeSnapper.MinimumDurationMinutes)
            step TimeSnapper.MinimumDurationMinutes
        ) {
            repeat(OverlapPolicy.MaxSavedOverlap) {
                blockDao().insertBlock(
                    PlannerBlockEntity(
                        id = id++,
                        dateEpochDay = today,
                        title = "Load $id",
                        startMinutes = start,
                        durationMinutes = TimeSnapper.MinimumDurationMinutes
                    )
                )
            }
        }
    }

    private fun visibleStartMinutes(): Int {
        val now = LocalTime.now()
        val latestSafeStart = TimeSnapper.MinutesPerDay -
            TimeSnapper.DefaultDurationMinutes -
            3 * TimeSnapper.MinutesPerHour
        return TimeSnapper.floorToSnap(now.hour * TimeSnapper.MinutesPerHour + now.minute)
            .coerceIn(0, latestSafeStart)
    }

    private fun storedBlock(title: String): PlannerBlockEntity {
        return checkNotNull(findStoredBlock(title)) { "No stored block titled $title" }
    }

    private fun findStoredBlock(title: String): PlannerBlockEntity? {
        val db = checkNotNull(database)
        return runBlocking {
            db.blockDao()
                .getBlocksForDate(LocalDate.now().toEpochDay())
                .firstOrNull { block -> block.title == title }
        }
    }

    private fun waitUntilStoredBlock(
        title: String,
        predicate: (PlannerBlockEntity) -> Boolean
    ): PlannerBlockEntity {
        var observed: PlannerBlockEntity? = null
        compose.waitUntil(timeoutMillis = 3_000) {
            findStoredBlock(title)?.also { observed = it }?.let(predicate) == true
        }
        return checkNotNull(observed)
    }

    private fun waitUntilStoredBlockMissing(title: String) {
        compose.waitUntil(timeoutMillis = 3_000) {
            findStoredBlock(title) == null
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilNodeWithText(
        text: String,
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilBlockExists(
        title: String,
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasContentDescription(title, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private class RecordingHapticFeedback(
    private val events: MutableList<HapticFeedbackType>
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        events += hapticFeedbackType
    }
}
