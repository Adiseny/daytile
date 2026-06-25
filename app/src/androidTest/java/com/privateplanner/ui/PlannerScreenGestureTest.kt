package com.privateplanner.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.data.PlannerBlockEntity
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerScreenGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var database: PlannerDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
        database = null
    }

    @Test
    fun tapEmptyTimeCreatesBlockAndOpensActions() {
        setPlannerContent()

        compose.onNodeWithTag(PlannerTestTags.Root)
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
    fun horizontalSwipeChangesDayAndBack() {
        setPlannerContent()

        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithTag(PlannerTestTags.Root).performTouchInput { swipeLeft() }
        compose.waitUntilNodeWithText("Tomorrow")
        compose.onNodeWithTag(PlannerTestTags.Root).performTouchInput { swipeRight() }
        compose.waitUntilNodeWithText("Today")
    }

    @Test
    fun blockDragResizeAndAccessibilityActionsRemainStable() {
        setPlannerContent {
            insertVisibleBlock(title = "Move me")
        }

        compose.waitUntilBlockExists()
        compose.onNodeWithTag(PlannerTestTags.TimeBlock).performTouchInput {
            down(center)
            advanceEventTime(450)
            moveBy(Offset(0f, 80f))
            up()
        }

        compose.waitUntilBlockExists()
        compose.onNodeWithTag(PlannerTestTags.TimeBlock).performTouchInput {
            down(Offset(centerX, bottom - 4f))
            moveBy(Offset(0f, 80f))
            up()
        }

        compose.waitUntilBlockExists()
        val actions = compose.onNodeWithTag(PlannerTestTags.TimeBlock)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertTrue(actions.first { it.label == "Lengthen 5 minutes" }.action())
        compose.waitUntilBlockExists()
    }

    @Test
    fun crowdedTimelineComposesWithinBudget() {
        val elapsedMs = measureElapsedMillis {
            setPlannerContent {
                seedCrowdedVisibleDay()
            }
            compose.waitUntilBlockExists(timeoutMillis = 5_000)
        }

        assertTrue("Crowded timeline composed in ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun largeFontCreateAndActionFlowRemainReachable() {
        setPlannerContent(fontScale = 1.6f)

        compose.onNodeWithTag(PlannerTestTags.Root)
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
    fun denseShortClusterOpensReadableOverlayRows() {
        setPlannerContent {
            val start = visibleStartMinutes()
            insertBlock(title = "First short", startMinutes = start, durationMinutes = 5)
            insertBlock(title = "Second short", startMinutes = start + 5, durationMinutes = 5)
        }

        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag(PlannerTestTags.ShortCluster).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(PlannerTestTags.ShortCluster).performClick()
        compose.onNodeWithTag(PlannerTestTags.ShortTaskOverlay).assertExists()
        compose.onNodeWithText("First short").assertExists()
        compose.onNodeWithText("Second short").assertExists()
        compose.onNodeWithText("First short").performClick()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithText("Earlier").assertDoesNotExist()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun compactShortChipBeforeMeetingOpensOverlayWithoutMovingMeeting() {
        setPlannerContent {
            val start = visibleStartMinutes()
            insertBlock(title = "Quick prep", startMinutes = start, durationMinutes = 5)
            insertBlock(title = "Meeting", startMinutes = start + 5, durationMinutes = 60)
        }

        compose.waitUntilNodeWithText("Meeting")
        compose.onNodeWithContentDescription("Quick prep", substring = true)
            .performTouchInput {
                down(Offset(centerX, height * 0.1f))
                up()
            }
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag(PlannerTestTags.ShortTaskOverlay).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(PlannerTestTags.ShortTaskOverlay).assertExists()
        compose.onNodeWithText("Quick prep").assertExists()
        compose.onNodeWithText("Meeting").assertExists()
    }

    private fun setPlannerContent(
        fontScale: Float = 1f,
        seed: suspend PlannerDatabase.() -> Unit = {}
    ) {
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
                LocalDensity provides Density(density = density.density, fontScale = fontScale)
            ) {
                PlannerTheme {
                    PlannerScreen(viewModel = viewModel)
                }
            }
        }
        compose.waitForIdle()
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
            step TimeSnapper.SnapMinutes
        ) {
            repeat(4) { overlapIndex ->
                blockDao().insertBlock(
                    PlannerBlockEntity(
                        id = id++,
                        dateEpochDay = today,
                        title = "Load $id",
                        startMinutes = start,
                        durationMinutes = TimeSnapper.DefaultDurationMinutes + overlapIndex * TimeSnapper.SnapMinutes
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

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilNodeWithText(
        text: String,
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilBlockExists(
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodesWithTag(PlannerTestTags.TimeBlock).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun measureElapsedMillis(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }
}
