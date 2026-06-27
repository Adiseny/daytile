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

        setPlannerContent()
        compose.waitUntilNodeWithText("Deep work")
        captureRoot(outputDir.resolve("daytile-timeline.png"))

        compose.runOnUiThread {
            viewModel.openCreate(10 * 60 + 45)
        }
        compose.waitUntilHasTextField()
        hideKeyboard()
        captureRoot(outputDir.resolve("daytile-create.png"))

        compose.runOnUiThread {
            viewModel.dismissSheet()
            viewModel.openActions(2L)
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
        val today = LocalDate.now().toEpochDay()
        val blocks = listOf(
            block(1, today, "Morning plan", 8 * 60, 35),
            block(2, today, "Deep work", 9 * 60, 120),
            block(3, today, "Design review", 9 * 60 + 30, 45),
            block(4, today, "Admin", 11 * 60 + 45, 15),
            block(5, today, "Lunch", 12 * 60 + 30, 45),
            block(6, today, "Build release", 14 * 60, 90),
            block(7, today, "Walk", 15 * 60 + 45, 30)
        )
        blocks.forEach { block ->
            blockDao().insertBlock(block)
        }
    }

    private fun block(
        id: Long,
        dateEpochDay: Long,
        title: String,
        startMinutes: Int,
        durationMinutes: Int
    ): PlannerBlockEntity {
        return PlannerBlockEntity(
            id = id,
            dateEpochDay = dateEpochDay,
            title = title,
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
    }

    private fun captureRoot(outputFile: File) {
        val source = compose.onRoot().captureToImage().asAndroidBitmap()
        val targetWidth = 540
        val targetHeight = (source.height * targetWidth / source.width.toFloat()).roundToInt()
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { stream ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
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
