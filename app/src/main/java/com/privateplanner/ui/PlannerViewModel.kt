package com.privateplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.OverlapPolicy
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
    private val repository: PlannerRepository
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val blockCache = MutableStateFlow<Map<LocalDate, List<PlannerBlock>>>(emptyMap())
    private val sheet = MutableStateFlow<PlannerSheet?>(null)
    private val snackbar = MutableStateFlow<PlannerSnackbar?>(null)
    private val createError = MutableStateFlow<String?>(null)
    private val scrollTargetMinutes = MutableStateFlow<Int?>(openingScrollTarget())
    private val pendingTimeUpdates = mutableMapOf<Long, PendingTimeUpdate>()
    private val timeWriteJobs = mutableMapOf<Long, Job>()

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
            val created = repository.createBlock(
                date = selectedDate.value,
                startMinutes = currentSheet.startMinutes,
                title = title
            )
            if (created) {
                sheet.value = null
            } else {
                createError.value = "No space here"
            }
        }
    }

    fun renameBlock(title: String) {
        val currentSheet = sheet.value as? PlannerSheet.RenameBlock ?: return
        if (title.isBlank()) return
        createError.value = null
        viewModelScope.launch {
            repository.updateTitle(currentSheet.blockId, title)
            sheet.value = null
        }
    }

    fun deleteBlock(blockId: Long) {
        val cached = cachedBlock(blockId)
        viewModelScope.launch {
            val block = cached ?: repository.getBlock(blockId) ?: return@launch
            timeWriteJobs.remove(blockId)?.cancel()
            pendingTimeUpdates.remove(blockId)
            repository.deleteBlock(block.id)
            sheet.value = null
            snackbar.value = PlannerSnackbar(
                id = System.nanoTime(),
                deletedBlock = block
            )
        }
    }

    fun undoDelete(snackbarId: Long) {
        val message = snackbar.value ?: return
        if (message.id != snackbarId) return
        viewModelScope.launch {
            repository.restoreBlock(message.deletedBlock)
            snackbar.value = null
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
        val candidate = block.copy(startMinutes = snappedStart)
        if (!OverlapPolicy.canPlace(cachedBlocksForSelectedDate(), candidate)) return false
        if (snappedStart == block.startMinutes) return true
        updateCachedTime(blockId, snappedStart, block.durationMinutes)
        scheduleTimeWrite(blockId, snappedStart, block.durationMinutes)
        return true
    }

    fun movePlacement(blockId: Long, startMinutes: Int): MovePlacement {
        val blocks = cachedBlocksForSelectedDate()
        val block = blocks.firstOrNull { block -> block.id == blockId } ?: return MovePlacement.Invalid
        val candidate = block.copy(startMinutes = snappedStart(block, startMinutes))
        val maxOverlap = OverlapPolicy.maxOverlap(blocks, candidate)
        return when {
            maxOverlap <= OverlapPolicy.MaxSavedOverlap -> MovePlacement.Savable
            maxOverlap <= OverlapPolicy.MaxTransientOverlap -> MovePlacement.TransientOnly
            else -> MovePlacement.Invalid
        }
    }

    fun resizeBlock(blockId: Long, durationMinutes: Int): Boolean {
        val block = cachedBlock(blockId) ?: return false
        val requestedDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            TimeSnapper.snapDurationToNearest(durationMinutes)
        )
        val fittedDuration = OverlapPolicy.largestValidDuration(
            blocks = cachedBlocksForSelectedDate(),
            candidate = block,
            preferredDurationMinutes = requestedDuration
        ) ?: return false
        if (fittedDuration == block.durationMinutes) return false
        updateCachedTime(blockId, block.startMinutes, fittedDuration)
        scheduleTimeWrite(blockId, block.startMinutes, fittedDuration)
        return true
    }

    fun canResizeBlock(
        blockId: Long,
        durationMinutes: Int,
        maxOverlap: Int = OverlapPolicy.MaxSavedOverlap
    ): Boolean {
        val block = cachedBlock(blockId) ?: return false
        val snappedDuration = TimeSnapper.clampDuration(
            block.startMinutes,
            TimeSnapper.snapDurationToNearest(durationMinutes)
        )
        val candidate = block.copy(durationMinutes = snappedDuration)
        return OverlapPolicy.canPlace(cachedBlocksForSelectedDate(), candidate, maxOverlap)
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
        listOf(date.minusDays(1), date.plusDays(1))
            .filterNot { day -> blockCache.value.containsKey(day) }
            .forEach { day ->
                viewModelScope.launch {
                    val blocks = repository.getBlocksForDate(day)
                    blockCache.value = blockCache.value + (day to blocks)
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

    private fun scheduleTimeWrite(blockId: Long, startMinutes: Int, durationMinutes: Int) {
        val pending = PendingTimeUpdate(startMinutes, durationMinutes)
        pendingTimeUpdates[blockId] = pending
        timeWriteJobs.remove(blockId)?.cancel()
        timeWriteJobs[blockId] = viewModelScope.launch {
            repository.updateTime(blockId, startMinutes, durationMinutes)
            if (pendingTimeUpdates[blockId] == pending) {
                pendingTimeUpdates.remove(blockId)
                timeWriteJobs.remove(blockId)
            }
        }
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
        val keep = setOf(previousDate, date.minusDays(1), date, date.plusDays(1))
        blockCache.value = blockCache.value.filterKeys { cachedDate -> cachedDate in keep }
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
