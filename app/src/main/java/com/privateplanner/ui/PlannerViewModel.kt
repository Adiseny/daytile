package com.privateplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privateplanner.Reminders
import com.privateplanner.data.PlannerRepository
import com.privateplanner.data.PlannerWriteResult
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlannerUiState(
    val selectedDate: LocalDate,
    // The selected day's blocks only: neighbours are cached privately, so a prefetch
    // publishes nothing and recomposes nothing.
    val blocks: List<PlannerBlock>,
    val sheet: PlannerSheet?,
    val snackbar: PlannerSnackbar?,
    val sheetError: String?,
    val remindersOn: Boolean
)

sealed interface PlannerSheet {
    class CreateBlock(val startMinutes: Int) : PlannerSheet
    class RenameBlock(val blockId: Long) : PlannerSheet
    class BlockActions(val blockId: Long) : PlannerSheet
    object DateJump : PlannerSheet
}

sealed interface PlannerSnackbar {
    val id: Long

    class Deleted(
        override val id: Long,
        val deletedBlock: PlannerBlock
    ) : PlannerSnackbar

    class Message(
        override val id: Long,
        val message: String
    ) : PlannerSnackbar
}

class PlannerViewModel(
    private val repository: PlannerRepository,
    private val reminders: Reminders
) : ViewModel() {
    private val launchedAt = TimeSnapper.localNowMillis()
    private val mutableUiState = MutableStateFlow(
        PlannerUiState(
            selectedDate = TimeSnapper.dateOf(launchedAt),
            blocks = emptyList(),
            sheet = null,
            snackbar = null,
            sheetError = null,
            // Already in memory: PlannerApp starts loading it before the activity exists.
            remindersOn = reminders.enabled
        )
    )
    val uiState: StateFlow<PlannerUiState> = mutableUiState.asStateFlow()

    // Where the timeline opens, taken once by its first layout. Outside the UI state, so
    // taking it publishes nothing and recomposes nothing.
    private var scrollTarget: Int? = TimeSnapper.minuteOfDay(launchedAt)

    fun takeScrollTarget(): Int? = scrollTarget.also { scrollTarget = null }

    // The selected day and its neighbours, for an instant swipe. Main thread only, like
    // everything here.
    private val dayCache = HashMap<LocalDate, List<PlannerBlock>>()
    private val pendingTimeUpdates = mutableMapOf<Long, PendingTimeUpdate>()
    private val timeWriteJobs = mutableMapOf<Long, Job>()
    private val prefetchJobs = mutableMapOf<LocalDate, Job>()
    private val deletingBlockIds = mutableSetOf<Long>()
    private var dateObservationJob: Job? = null
    private var sheetWriteJob: Job? = null
    private var undoDeleteJob: Job? = null

    init {
        observeDate(mutableUiState.value.selectedDate)
    }

    private fun observeDate(date: LocalDate) {
        dateObservationJob?.cancel()
        dateObservationJob = repository.observeBlocksForDate(date)
            .onEach { blocks ->
                publish(date, mergePendingTimeUpdates(blocks))
                if (mutableUiState.value.selectedDate == date) {
                    prefetchDate(date.minusDays(1))
                    prefetchDate(date.plusDays(1))
                }
            }
            .launchIn(viewModelScope)
    }

    // A re-query usually confirms what is already shown (the optimistic move, the
    // prefetched day), and republishing an equal list would recompose the whole screen.
    private fun publish(date: LocalDate, blocks: List<PlannerBlock>) {
        dayCache[date] = blocks
        mutableUiState.update { state ->
            if (state.selectedDate != date || state.blocks == blocks) state else state.copy(blocks = blocks)
        }
    }

    fun shiftDay(days: Long) {
        setDate(mutableUiState.value.selectedDate.plusDays(days))
    }

    fun returnToToday() {
        setDate(TimeSnapper.dateOf(TimeSnapper.localNowMillis()))
    }

    fun jumpTo(date: LocalDate) {
        setDate(date)
        dismissSheet()
    }

    fun openCreate(startMinutes: Int) {
        showSheet(PlannerSheet.CreateBlock(TimeSnapper.floorToValidStart(startMinutes)))
    }

    fun openActions(blockId: Long) {
        showSheet(PlannerSheet.BlockActions(blockId))
    }

    fun openRename(blockId: Long) {
        showSheet(PlannerSheet.RenameBlock(blockId))
    }

    fun openDateJump() {
        showSheet(PlannerSheet.DateJump)
    }

    fun dismissSheet() {
        showSheet(null)
    }

    fun createBlock(title: String) {
        val state = mutableUiState.value
        val currentSheet = state.sheet as? PlannerSheet.CreateBlock ?: return
        if (title.isBlank()) return
        if (sheetWriteJob?.isActive == true) return
        setSheetError(null)
        sheetWriteJob = viewModelScope.launch {
            val errorText = when (repository.createBlock(
                date = state.selectedDate,
                startMinutes = currentSheet.startMinutes,
                title = title
            )) {
                PlannerWriteResult.Success -> null
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap -> "No space here"
                PlannerWriteResult.InvalidInput -> "Title is too long"
                else -> "Could not save"
            }
            finishSheetWrite(currentSheet, errorText)
        }
    }

    fun renameBlock(title: String) {
        val currentSheet = mutableUiState.value.sheet as? PlannerSheet.RenameBlock ?: return
        if (title.isBlank()) return
        if (sheetWriteJob?.isActive == true) return
        setSheetError(null)
        sheetWriteJob = viewModelScope.launch {
            val errorText = when (repository.updateTitle(currentSheet.blockId, title)) {
                PlannerWriteResult.Success -> null
                PlannerWriteResult.InvalidInput -> "Title is too long"
                else -> "Could not save"
            }
            finishSheetWrite(currentSheet, errorText)
        }
    }

    // Only ever asked for a block on screen, whose displayed copy is what undo restores.
    fun deleteBlock(blockId: Long) {
        val block = cachedBlock(blockId) ?: return
        if (!deletingBlockIds.add(blockId)) return
        viewModelScope.launch {
            try {
                timeWriteJobs.remove(blockId)?.cancel()
                pendingTimeUpdates.remove(blockId)
                if (repository.deleteBlock(block.id) == PlannerWriteResult.Success) {
                    mutableUiState.update { state ->
                        state.copy(
                            sheet = null,
                            snackbar = PlannerSnackbar.Deleted(
                                id = System.nanoTime(),
                                deletedBlock = block
                            )
                        )
                    }
                } else {
                    showMessage("Could not delete")
                }
            } finally {
                deletingBlockIds.remove(blockId)
            }
        }
    }

    fun undoDelete(snackbarId: Long) {
        val message = mutableUiState.value.snackbar as? PlannerSnackbar.Deleted ?: return
        if (message.id != snackbarId) return
        if (undoDeleteJob?.isActive == true) return
        undoDeleteJob = viewModelScope.launch {
            when (repository.restoreBlock(message.deletedBlock)) {
                PlannerWriteResult.Success -> clearSnackbar(message.id)
                PlannerWriteResult.RejectedOverlap -> showMessage("Could not restore; that time is no longer available")
                else -> showMessage("Could not restore")
            }
        }
    }

    fun clearSnackbar(snackbarId: Long) {
        if (mutableUiState.value.snackbar?.id == snackbarId) {
            mutableUiState.update { it.copy(snackbar = null) }
        }
    }

    fun moveBlock(blockId: Long, startMinutes: Int): Boolean {
        val block = cachedBlock(blockId) ?: return false
        val snappedStart = TimeSnapper.clampStart(
            TimeSnapper.floorToSnap(startMinutes),
            block.durationMinutes
        )
        if (snappedStart == block.startMinutes) return true
        updateCachedTime(blockId, snappedStart, block.durationMinutes)
        scheduleTimeWrite(blockId, block.date, snappedStart, block.durationMinutes)
        return true
    }

    fun resizeBlock(blockId: Long, durationMinutes: Int): Boolean {
        val block = cachedBlock(blockId) ?: return false
        val requestedDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            TimeSnapper.snapDurationToNearest(durationMinutes)
        )
        if (requestedDuration == block.durationMinutes) return false
        updateCachedTime(blockId, block.startMinutes, requestedDuration)
        scheduleTimeWrite(blockId, block.date, block.startMinutes, requestedDuration)
        return true
    }

    fun setRemindersOn(on: Boolean) {
        reminders.enabled = on
        mutableUiState.update { it.copy(remindersOn = on) }
        reminders.syncSoon(repository)
    }

    fun notificationsBlocked() {
        showMessage("Turn on notifications in system settings")
    }

    private fun setDate(date: LocalDate) {
        if (date == mutableUiState.value.selectedDate) return
        prefetchJobs.keys.filterNot { it.isNear(date) }.forEach { staleDate ->
            prefetchJobs.remove(staleDate)?.cancel()
        }
        dayCache.keys.retainAll { it.isNear(date) }
        mutableUiState.update { it.copy(selectedDate = date, blocks = dayCache[date].orEmpty()) }
        observeDate(date)
    }

    private fun prefetchDate(date: LocalDate) {
        if (dayCache.containsKey(date) || prefetchJobs.containsKey(date)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val blocks = repository.getBlocksForDate(date)
                // Never over the live observer's result: once it has published this day,
                // a prefetch that started earlier can only be older.
                if (!dayCache.containsKey(date) && date.isNear(mutableUiState.value.selectedDate)) {
                    publish(date, blocks)
                }
            } finally {
                if (prefetchJobs[date] === coroutineContext[Job]) {
                    prefetchJobs.remove(date)
                }
            }
        }
        prefetchJobs[date] = job
        job.start()
    }

    private fun cachedBlock(blockId: Long): PlannerBlock? =
        mutableUiState.value.blocks.firstOrNull { block -> block.id == blockId }

    private fun updateCachedTime(blockId: Long, startMinutes: Int, durationMinutes: Int) {
        val state = mutableUiState.value
        val blockIndex = state.blocks.indexOfFirst { it.id == blockId }
        if (blockIndex < 0) return
        val updatedBlocks = ArrayList(state.blocks)
        updatedBlocks[blockIndex] = state.blocks[blockIndex].copy(
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
        updatedBlocks.sortWith(PlannerBlockOrder)
        publish(state.selectedDate, updatedBlocks)
    }

    private fun scheduleTimeWrite(
        blockId: Long,
        date: LocalDate,
        startMinutes: Int,
        durationMinutes: Int
    ) {
        val pending = PendingTimeUpdate(startMinutes, durationMinutes)
        pendingTimeUpdates[blockId] = pending
        timeWriteJobs.remove(blockId)?.cancel()
        timeWriteJobs[blockId] = viewModelScope.launch {
            val result = repository.updateTime(blockId, startMinutes, durationMinutes)
            if (pendingTimeUpdates[blockId] !== pending) return@launch
            pendingTimeUpdates.remove(blockId)
            timeWriteJobs.remove(blockId)
            if (result != PlannerWriteResult.Success) {
                publish(date, mergePendingTimeUpdates(repository.getBlocksForDate(date)))
                showMessage("Could not save")
            }
        }
    }

    private fun mergePendingTimeUpdates(blocks: List<PlannerBlock>): List<PlannerBlock> {
        if (pendingTimeUpdates.isEmpty()) return blocks
        if (blocks.none { pendingTimeUpdates.containsKey(it.id) }) return blocks
        return blocks.mapTo(ArrayList(blocks.size)) { block ->
            pendingTimeUpdates[block.id]?.let { pending ->
                block.copy(
                    startMinutes = pending.startMinutes,
                    durationMinutes = pending.durationMinutes
                )
            } ?: block
        }.apply {
            sortWith(PlannerBlockOrder)
        }
    }

    private fun showMessage(message: String) {
        mutableUiState.update {
            it.copy(
                snackbar = PlannerSnackbar.Message(
                    id = System.nanoTime(),
                    message = message
                )
            )
        }
    }

    // A sheet write keeps running if the sheet goes away mid-flight: the user asked
    // for it, so a late failure surfaces as a snackbar instead of a silently lost write.
    private fun finishSheetWrite(sheet: PlannerSheet, errorText: String?) {
        if (mutableUiState.value.sheet === sheet) {
            if (errorText == null) showSheet(null) else setSheetError(errorText)
        } else if (errorText != null) {
            showMessage(errorText)
        }
    }

    private fun showSheet(value: PlannerSheet?) {
        mutableUiState.update { state ->
            if (state.sheet === value && state.sheetError == null) {
                state
            } else {
                state.copy(sheet = value, sheetError = null)
            }
        }
    }

    private fun setSheetError(message: String?) {
        if (mutableUiState.value.sheetError != message) {
            mutableUiState.update { it.copy(sheetError = message) }
        }
    }
}

private class PendingTimeUpdate(
    val startMinutes: Int,
    val durationMinutes: Int
)

private fun LocalDate.isNear(day: LocalDate): Boolean = abs(toEpochDay() - day.toEpochDay()) <= 1
