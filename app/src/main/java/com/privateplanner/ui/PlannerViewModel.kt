package com.privateplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privateplanner.data.PlannerRepository
import com.privateplanner.data.PlannerWriteResult
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlannerViewModel(
    private val repository: PlannerRepository,
    initialScrollTargetMinutes: Int? = openingScrollTarget()
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val blockCache = MutableStateFlow<Map<LocalDate, List<PlannerBlock>>>(emptyMap())
    private val sheet = MutableStateFlow<PlannerSheet?>(null)
    private val snackbar = MutableStateFlow<PlannerSnackbar?>(null)
    private val createError = MutableStateFlow<String?>(null)
    private val scrollTargetMinutes = MutableStateFlow(initialScrollTargetMinutes)
    private val pendingTimeUpdates = mutableMapOf<Long, PendingTimeUpdate>()
    private val timeWriteJobs = mutableMapOf<Long, Job>()
    private val prefetchJobs = mutableMapOf<LocalDate, Job>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedDateBlocks = selectedDate
        .flatMapLatest { date ->
            repository.observeBlocksForDate(date)
                .onEach { blocks ->
                    blockCache.value = blockCache.value + (date to mergePendingTimeUpdates(blocks))
                    if (selectedDate.value == date) {
                        prefetchAdjacent(date)
                    }
                }
        }

    private val baseUiState = combine(
        selectedDate,
        blockCache,
        sheet,
        snackbar,
        createError
    ) { date, cachedBlocks, currentSheet, currentSnackbar, currentCreateError ->
        PlannerUiState(
            selectedDate = date,
            blocksByDate = cachedBlocks,
            selectedDateLoaded = cachedBlocks.containsKey(date),
            sheet = currentSheet,
            snackbar = currentSnackbar,
            createError = currentCreateError,
            scrollTargetMinutes = null
        )
    }

    val uiState: StateFlow<PlannerUiState> = combine(
        baseUiState,
        scrollTargetMinutes
    ) { state, target ->
        state.copy(scrollTargetMinutes = target)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlannerUiState(
            selectedDate = selectedDate.value,
            blocksByDate = emptyMap(),
            selectedDateLoaded = false,
            sheet = null,
            snackbar = null,
            createError = null,
            scrollTargetMinutes = scrollTargetMinutes.value
        )
    )

    init {
        selectedDateBlocks.launchIn(viewModelScope)
    }

    fun previousDay() {
        setDate(selectedDate.value.minusDays(1))
    }

    fun nextDay() {
        setDate(selectedDate.value.plusDays(1))
    }

    fun returnToToday() {
        setDate(LocalDate.now())
    }

    fun jumpTo(date: LocalDate) {
        setDate(date)
        dismissSheet()
    }

    fun openCreate(startMinutes: Int) {
        createError.value = null
        sheet.value = PlannerSheet.CreateBlock(TimeSnapper.floorToValidStart(startMinutes))
    }

    fun openActions(blockId: Long) {
        createError.value = null
        sheet.value = PlannerSheet.BlockActions(blockId)
    }

    fun openRename(blockId: Long) {
        createError.value = null
        sheet.value = PlannerSheet.RenameBlock(blockId)
    }

    fun openDateJump() {
        createError.value = null
        sheet.value = PlannerSheet.DateJump
    }

    fun dismissSheet() {
        createError.value = null
        sheet.value = null
    }

    fun createBlock(title: String) {
        val currentSheet = sheet.value as? PlannerSheet.CreateBlock ?: return
        if (title.isBlank()) return
        createError.value = null
        viewModelScope.launch {
            when (repository.createBlock(
                date = selectedDate.value,
                startMinutes = currentSheet.startMinutes,
                title = title
            )) {
                PlannerWriteResult.Success -> sheet.value = null
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap -> createError.value = "No space here"
                PlannerWriteResult.InvalidInput -> createError.value = "Title is too long"
                PlannerWriteResult.MissingBlock,
                is PlannerWriteResult.Failed -> createError.value = "Could not save"
            }
        }
    }

    fun renameBlock(title: String) {
        val currentSheet = sheet.value as? PlannerSheet.RenameBlock ?: return
        if (title.isBlank()) return
        createError.value = null
        viewModelScope.launch {
            when (repository.updateTitle(currentSheet.blockId, title)) {
                PlannerWriteResult.Success -> sheet.value = null
                PlannerWriteResult.InvalidInput -> createError.value = "Title is too long"
                PlannerWriteResult.MissingBlock,
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap,
                is PlannerWriteResult.Failed -> createError.value = "Could not save"
            }
        }
    }

    fun deleteBlock(blockId: Long) {
        val cached = cachedBlock(blockId)
        viewModelScope.launch {
            val block = cached ?: repository.getBlock(blockId) ?: return@launch
            timeWriteJobs.remove(blockId)?.cancel()
            pendingTimeUpdates.remove(blockId)
            when (repository.deleteBlock(block.id)) {
                PlannerWriteResult.Success -> {
                    sheet.value = null
                    snackbar.value = PlannerSnackbar.Deleted(
                        id = System.nanoTime(),
                        deletedBlock = block
                    )
                }
                PlannerWriteResult.MissingBlock,
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap,
                PlannerWriteResult.InvalidInput,
                is PlannerWriteResult.Failed -> showMessage("Could not delete")
            }
        }
    }

    fun undoDelete(snackbarId: Long) {
        val message = snackbar.value as? PlannerSnackbar.Deleted ?: return
        if (message.id != snackbarId) return
        viewModelScope.launch {
            when (repository.restoreBlock(message.deletedBlock)) {
                PlannerWriteResult.Success -> snackbar.value = null
                PlannerWriteResult.RejectedOverlap -> showMessage("Could not restore; that time is no longer available")
                PlannerWriteResult.MissingBlock,
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.InvalidInput,
                is PlannerWriteResult.Failed -> showMessage("Could not restore")
            }
        }
    }

    fun clearSnackbar(snackbarId: Long) {
        val message = snackbar.value ?: return
        if (message.id == snackbarId) {
            snackbar.value = null
        }
    }

    fun moveBlock(blockId: Long, startMinutes: Int): Boolean {
        val block = cachedBlock(blockId) ?: return false
        val snappedStart = snappedStart(block, startMinutes)
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

    fun consumeScrollTarget() {
        scrollTargetMinutes.value = null
    }

    private fun setDate(date: LocalDate) {
        val previousDate = selectedDate.value
        selectedDate.value = date
        trimCache(date, previousDate)
    }

    private fun prefetchAdjacent(date: LocalDate) {
        prefetchDate(date.minusDays(1))
        prefetchDate(date.plusDays(1))
    }

    private fun prefetchDate(date: LocalDate) {
        if (blockCache.value.containsKey(date)) return
        if (prefetchJobs.containsKey(date)) return
        prefetchJobs[date] = viewModelScope.launch {
            try {
                val blocks = repository.getBlocksForDate(date)
                blockCache.value = blockCache.value + (date to blocks)
            } finally {
                prefetchJobs.remove(date)
            }
        }
    }

    private fun cachedBlock(blockId: Long): PlannerBlock? {
        return cachedBlocksForSelectedDate().firstOrNull { block -> block.id == blockId }
    }

    private fun cachedBlocksForSelectedDate(): List<PlannerBlock> {
        return blockCache.value[selectedDate.value].orEmpty()
    }

    private fun updateCachedTime(blockId: Long, startMinutes: Int, durationMinutes: Int) {
        val date = selectedDate.value
        val updatedBlocks = blockCache.value[date].orEmpty()
            .map { block ->
                if (block.id == blockId) {
                    block.copy(startMinutes = startMinutes, durationMinutes = durationMinutes)
                } else {
                    block
                }
            }
            .sortedWith(PlannerBlockOrder)
        blockCache.value = blockCache.value + (date to updatedBlocks)
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
            if (pendingTimeUpdates[blockId] != pending) return@launch
            pendingTimeUpdates.remove(blockId)
            timeWriteJobs.remove(blockId)
            when (result) {
                PlannerWriteResult.Success -> Unit
                PlannerWriteResult.MissingBlock,
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap,
                PlannerWriteResult.InvalidInput,
                is PlannerWriteResult.Failed -> {
                    reloadDate(date)
                    showMessage("Could not save")
                }
            }
        }
    }

    private suspend fun reloadDate(date: LocalDate) {
        val blocks = repository.getBlocksForDate(date)
        blockCache.value = blockCache.value + (date to mergePendingTimeUpdates(blocks))
    }

    private fun mergePendingTimeUpdates(blocks: List<PlannerBlock>): List<PlannerBlock> {
        if (pendingTimeUpdates.isEmpty()) return blocks
        return blocks
            .map { block ->
                pendingTimeUpdates[block.id]?.let { pending ->
                    block.copy(
                        startMinutes = pending.startMinutes,
                        durationMinutes = pending.durationMinutes
                    )
                } ?: block
            }
            .sortedWith(PlannerBlockOrder)
    }

    private fun snappedStart(block: PlannerBlock, startMinutes: Int): Int {
        return TimeSnapper.clampStart(
            TimeSnapper.floorToSnap(startMinutes),
            block.durationMinutes
        )
    }

    private fun trimCache(date: LocalDate, previousDate: LocalDate) {
        val previousDay = date.minusDays(1)
        val nextDay = date.plusDays(1)
        blockCache.value = blockCache.value.filterKeys { cachedDate ->
            cachedDate == previousDate ||
                cachedDate == previousDay ||
                cachedDate == date ||
                cachedDate == nextDay
        }
    }

    private fun showMessage(message: String) {
        snackbar.value = PlannerSnackbar.Message(
            id = System.nanoTime(),
            message = message
        )
    }

    companion object {
        fun factory(repository: PlannerRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlannerViewModel(repository) as T
                }
            }
        }
    }
}

private data class PendingTimeUpdate(
    val startMinutes: Int,
    val durationMinutes: Int
)

private fun openingScrollTarget(): Int = TimeSnapper.minuteOfDay(LocalTime.now())
