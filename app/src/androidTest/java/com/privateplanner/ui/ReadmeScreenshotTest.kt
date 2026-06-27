package com.privateplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateplanner.data.PlannerBlockEntity
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var database: PlannerDatabase? = null
    private lateinit var viewModel: PlannerViewModel

    @After
    fun closeDatabase() {
        database?.close()
        database = null
    }

    @Test
    fun captureReadmeScreenshots() {
        val outputDir = screenshotOutputDir()
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val today = LocalDate.now()
        setPlannerContent()
        compose.waitUntilNodeWithText("Pack school bags")
        captureRoot(outputDir.resolve("daytile-timeline.png"))

        compose.runOnUiThread {
            viewModel.jumpTo(today.plusDays(1))
        }
        compose.waitUntilNodeWithText("Review lecture notes")
        compose.runOnUiThread {
            viewModel.openCreate(10 * 60 + 45)
        }
        compose.waitUntilHasTextField()
        compose.onNode(hasSetTextAction()).performTextInput("Book study room")
        hideKeyboard()
        captureRoot(outputDir.resolve("daytile-create.png"))

        compose.runOnUiThread {
            viewModel.jumpTo(today.plusDays(2))
        }
        compose.waitUntilNodeWithText("Fit client dress")
        compose.runOnUiThread {
            viewModel.openActions(302L)
        }
        compose.onNodeWithText("Delete").assertExists()
        captureRoot(outputDir.resolve("daytile-actions.png"))
    }

    private fun setPlannerContent() {
        val db = Room.inMemoryDatabaseBuilder(
            compose.activity.applicationContext,
            PlannerDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        database = db
        runBlocking { db.seedReadmeBlocks() }

        viewModel = PlannerViewModel(
            repository = PlannerRepository(db),
            initialScrollTargetMinutes = 8 * 60
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1f)
            ) {
                PlannerTheme(currentMinuteOverride = 10 * 60 + 30) {
                    PlannerScreen(viewModel = viewModel)
                }
            }
        }
        compose.waitForIdle()
    }

    private suspend fun PlannerDatabase.seedReadmeBlocks() {
        val today = LocalDate.now()
        val blocks = listOf(
            block(101, today, "Pack school bags", 8 * 60, 30),
            block(102, today, "Clear kitchen for builder", 9 * 60, 120),
            block(103, today, "Drive to dentist", 9 * 60 + 30, 45),
            block(104, today, "Call plumber", 11 * 60 + 45, 15),
            block(105, today, "Cook lunch with Mum", 12 * 60 + 30, 45),
            block(106, today, "Take kit to football", 16 * 60, 60),
            block(201, today.plusDays(1), "Review lecture notes", 8 * 60 + 30, 90),
            block(202, today.plusDays(1), "Draft seminar answer", 10 * 60 + 15, 30),
            block(203, today.plusDays(1), "Rehearse group slides", 11 * 60 + 15, 45),
            block(204, today.plusDays(1), "Work library desk", 13 * 60, 120),
            block(205, today.plusDays(1), "Catch train home", 16 * 60 + 30, 35),
            block(301, today.plusDays(2), "Set up studio", 8 * 60, 45),
            block(302, today.plusDays(2), "Fit client dress", 9 * 60 + 15, 75),
            block(303, today.plusDays(2), "Check supplier delivery", 10 * 60, 60),
            block(304, today.plusDays(2), "Pack online orders", 11 * 60 + 30, 45),
            block(305, today.plusDays(2), "Write staff rota", 12 * 60 + 45, 25)
        )
        blocks.forEach { block ->
            blockDao().insertBlock(block)
        }
    }

    private fun block(
        id: Long,
        date: LocalDate,
        title: String,
        startMinutes: Int,
        durationMinutes: Int
    ): PlannerBlockEntity {
        return PlannerBlockEntity(
            id = id,
            dateEpochDay = date.toEpochDay(),
            title = title,
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
    }

    private fun captureRoot(outputFile: File) {
        val source = compose.onRoot().captureToImage().asAndroidBitmap()
        val topCropPx = screenshotTopCropPx(source)
        val cropped = Bitmap.createBitmap(
            source,
            0,
            topCropPx,
            source.width,
            source.height - topCropPx
        )
        val targetWidth = 540
        val targetHeight = (cropped.height * targetWidth / cropped.width.toFloat()).roundToInt()
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { stream ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun screenshotTopCropPx(source: Bitmap): Int {
        val resources = compose.activity.resources
        val statusBarResourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarResourceId > 0) {
            resources.getDimensionPixelSize(statusBarResourceId)
        } else {
            0
        }
        return statusBarHeight.coerceIn(0, source.height / 8)
    }

    private fun screenshotOutputDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "readme-screenshots")
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val inputMethodManager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            compose.activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilNodeWithText(
        text: String,
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilHasTextField(
        timeoutMillis: Long = 3_000
    ) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
