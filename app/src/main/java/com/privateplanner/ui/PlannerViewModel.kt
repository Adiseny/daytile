package com.privateplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.privateplanner.Reminders
import com.privateplanner.data.PlannerRepository
import com.privateplanner.data.PlannerWriteResult
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlannerViewModel(
    private val repository: PlannerRepository,
    private val reminders: Reminders
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        LocalDateTime.now().let { now ->
            PlannerUiState(
                selectedDate = now.toLocalDate(),
                blocksByDate = emptyMap(),
                sheet = null,
                snackbar = null,
                sheetError = null,
                scrollTargetMinutes = TimeSnapper.minuteOfDay(now.toLocalTime()),
                remindersOn = reminders.enabled
            )
        }
    )
    val uiState: StateFlow<PlannerUiState> = mutableUiState.asStateFlow()

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
                mutableUiState.update { state ->
                    state.copy(
                        blocksByDate = state.blocksByDate +
                            (date to mergePendingTimeUpdates(blocks))
                    )
                }
                if (mutableUiState.value.selectedDate == date) {
                    prefetchDate(date.minusDays(1))
                    prefetchDate(date.plusDays(1))
                }
            }
            .launchIn(viewModelScope)
    }

    fun previousDay() {
        setDate(mutableUiState.value.selectedDate.minusDays(1))
    }

    fun nextDay() {
        setDate(mutableUiState.value.selectedDate.plusDays(1))
    }

    fun returnToToday() {
        setDate(LocalDate.now())
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

    fun deleteBlock(blockId: Long) {
        if (!deletingBlockIds.add(blockId)) return
        val cached = cachedBlock(blockId)
        viewModelScope.launch {
            try {
                val block = cached ?: repository.getBlock(blockId) ?: return@launch
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
        val message = mutableUiState.value.snackbar ?: return
        if (message.id == snackbarId) {
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
        viewModelScope.launch { reminders.sync(repository) }
    }

    fun notificationsBlocked() {
        showMessage("Turn on notifications in system settings")
    }

    fun consumeScrollTarget() {
        mutableUiState.update { it.copy(scrollTargetMinutes = null) }
    }

    private fun setDate(date: LocalDate) {
        if (date == mutableUiState.value.selectedDate) return
        val previousDay = date.minusDays(1)
        val nextDay = date.plusDays(1)
        fun isRetained(cachedDate: LocalDate): Boolean {
            return cachedDate == previousDay ||
                cachedDate == date ||
                cachedDate == nextDay
        }
        prefetchJobs.keys.filterNot(::isRetained).forEach { staleDate ->
            prefetchJobs.remove(staleDate)?.cancel()
        }
        mutableUiState.update { state ->
            state.copy(
                selectedDate = date,
                blocksByDate = state.blocksByDate.filterKeys(::isRetained)
            )
        }
        observeDate(date)
    }

    private fun prefetchDate(date: LocalDate) {
        if (mutableUiState.value.blocksByDate.containsKey(date)) return
        if (prefetchJobs.containsKey(date)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val blocks = repository.getBlocksForDate(date)
                mutableUiState.update { state ->
                    if (date in state.selectedDate.minusDays(1)..state.selectedDate.plusDays(1)) {
                        state.copy(blocksByDate = state.blocksByDate + (date to blocks))
                    } else {
                        state
                    }
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

    private fun cachedBlock(blockId: Long): PlannerBlock? {
        val state = mutableUiState.value
        return state.blocksByDate[state.selectedDate]
            .orEmpty()
            .firstOrNull { block -> block.id == blockId }
    }

    private fun updateCachedTime(blockId: Long, startMinutes: Int, durationMinutes: Int) {
        mutableUiState.update { state ->
            val date = state.selectedDate
            val currentBlocks = state.blocksByDate[date].orEmpty()
            val blockIndex = currentBlocks.indexOfFirst { it.id == blockId }
            if (blockIndex < 0) return@update state
            val updatedBlocks = ArrayList(currentBlocks)
            updatedBlocks[blockIndex] = currentBlocks[blockIndex].copy(
                startMinutes = startMinutes,
                durationMinutes = durationMinutes
            )
            updatedBlocks.sortWith(PlannerBlockOrder)
            state.copy(blocksByDate = state.blocksByDate + (date to updatedBlocks))
        }
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
                val blocks = repository.getBlocksForDate(date)
                mutableUiState.update { state ->
                    state.copy(
                        blocksByDate = state.blocksByDate +
                            (date to mergePendingTimeUpdates(blocks))
                    )
                }
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

    companion object {
        fun factory(
            repository: PlannerRepository,
            reminders: Reminders
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PlannerViewModel(repository, reminders)
                }
            }
    }
}

private class PendingTimeUpdate(
    val startMinutes: Int,
    val durationMinutes: Int
)
