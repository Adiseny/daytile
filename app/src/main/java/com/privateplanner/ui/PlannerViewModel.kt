package com.privateplanner.ui

import com.privateplanner.Reminders
import com.privateplanner.Worker
import com.privateplanner.data.PlannerRepository
import com.privateplanner.data.PlannerWriteResult
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.isBlankTitle
import com.privateplanner.onMain
import java.time.LocalDate
import java.util.Collections
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.abs

sealed interface PlannerSheet {
    class CreateBlock(val startMinutes: Int) : PlannerSheet
    class RenameBlock(val blockId: Long) : PlannerSheet
    class BlockActions(val blockId: Long) : PlannerSheet
    object DateJump : PlannerSheet
}

// A message above the navigation bar. One for a deleted block holds it, to put it back.
class PlannerSnackbar(val id: Long, val message: String, val deletedBlock: PlannerBlock? = null)

// What the planner shows and everything that changes it. Main thread only: reads and
// writes run on the worker thread and their results come back here, in the order they ran.
// It outlives a change of configuration with its activity.
class PlannerViewModel(
    private val repository: PlannerRepository,
    private val reminders: Reminders
) {
    private val launchedAt = TimeSnapper.localNowMillis()

    @Volatile // The worker skips queued reads that are no longer near this day.
    var selectedDate: LocalDate = TimeSnapper.dateOf(launchedAt)
        private set

    // The selected day's blocks only: its neighbours are cached privately, so reading
    // them ahead changes nothing on screen.
    var blocks: List<PlannerBlock> = Collections.emptyList()
        private set

    var sheet: PlannerSheet? = null
        private set

    var sheetError: String? = null
        private set

    var snackbar: PlannerSnackbar? = null
        private set

    val remindersOn: Boolean get() = reminders.enabled

    // The screen, while it is showing: called after every change to the state above.
    var onChange: Runnable? = null

    // Where the timeline opens, taken once by its first layout.
    private var scrollTarget: Int? = TimeSnapper.minuteOfDay(launchedAt)

    fun takeScrollTarget(): Int? = scrollTarget.also { scrollTarget = null }

    // The selected day and its neighbours, for an instant swipe.
    private val dayCache = HashMap<LocalDate, List<PlannerBlock>>()
    private val loadingDays = HashSet<LocalDate>()

    // Moves and resizes that are on screen but not yet saved, by block.
    private val pendingTimes = HashMap<Long, PendingTime>()
    private val deletingBlockIds = HashSet<Long>()
    private var sheetWriting = false
    private var undoing = false

    init {
        load(selectedDate)
    }

    private fun changed() {
        onChange?.run()
    }

    // Only missing days go to the worker. All writes publish their day on this thread,
    // so cached days stay current, including when a save finishes after a day swipe.
    private fun load(date: LocalDate) {
        val days = ArrayList<LocalDate>(3)
        for (offset in 0..2) {
            val day = date.plusDays(if (offset == 2) -1 else offset.toLong())
            if (!dayCache.containsKey(day) && loadingDays.add(day)) days += day
        }
        if (days.isEmpty()) return
        Worker.execute {
            val read = days.map { if (it.isNear(selectedDate)) repository.getBlocksForDate(it) else null }
            onMain {
                var retry = false
                for (index in days.indices) {
                    val day = days[index]
                    loadingDays.remove(day)
                    val blocks = read[index]
                    if (blocks != null) publish(day, blocks) else if (day.isNear(selectedDate)) retry = true
                }
                // The user can return to a day between a skipped read and this callback.
                if (retry) load(selectedDate)
            }
        }
    }

    // A read usually confirms what is already shown (the move made ahead of its save, the
    // cached day), and then the list on screen is kept, so nothing is drawn again.
    private fun publish(date: LocalDate, read: List<PlannerBlock>) {
        if (!date.isNear(selectedDate)) return
        val selected = date == selectedDate
        val merged = withPendingTimes(read)
        val shown = if (selected && merged == blocks) blocks else merged
        dayCache[date] = shown
        if (selected && shown !== blocks) {
            blocks = shown
            changed()
        }
    }

    // A write, then its day as the write left it, in one trip to the worker.
    private fun write(date: LocalDate, action: Supplier<PlannerWriteResult>, done: Consumer<PlannerWriteResult>) {
        Worker.execute {
            val result = action.get()
            val read = repository.getBlocksForDate(date)
            onMain {
                done.accept(result)
                publish(date, read)
            }
        }
    }

    fun shiftDay(days: Long) {
        setDate(selectedDate.plusDays(days))
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
        val current = sheet as? PlannerSheet.CreateBlock ?: return
        val date = selectedDate
        sheetWrite(current, title) { repository.createBlock(date, current.startMinutes, title) }
    }

    fun renameBlock(title: String) {
        val current = sheet as? PlannerSheet.RenameBlock ?: return
        // A rename sheet is open only over its block's day.
        val date = selectedDate
        sheetWrite(current, title) { repository.updateTitle(date, current.blockId, title) }
    }

    // A sheet's write keeps running if the sheet goes away meanwhile: the user asked for
    // it, so a late failure surfaces as a message instead of a silently lost write.
    private fun sheetWrite(current: PlannerSheet, title: String, action: Supplier<PlannerWriteResult>) {
        if (title.isBlankTitle() || sheetWriting) return
        sheetWriting = true
        setSheetError(null)
        write(selectedDate, action) { result ->
            sheetWriting = false
            val error = when (result) {
                PlannerWriteResult.Success -> null
                PlannerWriteResult.NoSpace,
                PlannerWriteResult.RejectedOverlap -> "No space here"
                PlannerWriteResult.InvalidInput -> "Title is too long"
                else -> "Could not save"
            }
            if (sheet === current) {
                if (error == null) showSheet(null) else setSheetError(error)
            } else if (error != null) {
                showMessage(error)
            }
        }
    }

    // Only ever asked for a block on screen, whose displayed copy is what undo restores.
    fun deleteBlock(blockId: Long) {
        val block = block(blockId) ?: return
        if (!deletingBlockIds.add(blockId)) return
        pendingTimes.remove(blockId)
        write(block.date, { repository.deleteBlock(block.date, block.id) }) { result ->
            deletingBlockIds.remove(blockId)
            if (result == PlannerWriteResult.Success) {
                sheet = null
                sheetError = null
                snackbar = PlannerSnackbar(System.nanoTime(), "Deleted", block)
                changed()
            } else {
                showMessage("Could not delete")
            }
        }
    }

    fun undoDelete(snackbarId: Long) {
        val block = snackbar?.takeIf { it.id == snackbarId }?.deletedBlock
        if (block == null || undoing) return
        undoing = true
        write(block.date, { repository.restoreBlock(block) }) { result ->
            undoing = false
            when (result) {
                PlannerWriteResult.Success -> clearSnackbar(snackbarId)
                PlannerWriteResult.RejectedOverlap -> showMessage("Could not restore; that time is no longer available")
                else -> showMessage("Could not restore")
            }
        }
    }

    fun clearSnackbar(snackbarId: Long) {
        if (snackbar?.id != snackbarId) return
        snackbar = null
        changed()
    }

    fun moveBlock(blockId: Long, startMinutes: Int): Boolean {
        val block = block(blockId) ?: return false
        val start = TimeSnapper.clampStart(TimeSnapper.floorToSnap(startMinutes), block.durationMinutes)
        if (start != block.startMinutes) setTime(block, start, block.durationMinutes)
        return true
    }

    fun resizeBlock(blockId: Long, durationMinutes: Int): Boolean {
        val block = block(blockId) ?: return false
        val duration = TimeSnapper.clampDuration(block.startMinutes, TimeSnapper.snapDurationToNearest(durationMinutes))
        if (duration == block.durationMinutes) return false
        setTime(block, block.startMinutes, duration)
        return true
    }

    // On screen at once and saved behind. A save that fails leaves the day as stored.
    private fun setTime(block: PlannerBlock, startMinutes: Int, durationMinutes: Int) {
        val pending = PendingTime(startMinutes, durationMinutes)
        pendingTimes[block.id] = pending
        publish(block.date, blocks)
        write(block.date, { repository.updateTime(block.date, block.id, startMinutes, durationMinutes) }) { result ->
            // A later change to the same block has taken over, and its result settles both.
            if (pendingTimes[block.id] === pending) {
                pendingTimes.remove(block.id)
                if (result != PlannerWriteResult.Success) showMessage("Could not save")
            }
        }
    }

    fun setRemindersOn(on: Boolean) {
        reminders.enabled = on
        changed()
        reminders.syncSoon()
    }

    // The date sheet that asked covers the bottom of the screen, where the message appears,
    // so it closes: the message would otherwise come and go unseen beneath it.
    fun notificationsBlocked() {
        dismissSheet()
        showMessage("Turn on notifications in system settings")
    }

    private fun setDate(date: LocalDate) {
        if (date == selectedDate) return
        dayCache.keys.retainAll { it.isNear(date) }
        selectedDate = date
        blocks = dayCache[date] ?: Collections.emptyList()
        changed()
        load(date)
    }

    private fun block(blockId: Long): PlannerBlock? = blocks.firstOrNull { it.id == blockId }

    private fun withPendingTimes(blocks: List<PlannerBlock>): List<PlannerBlock> {
        if (pendingTimes.isEmpty()) return blocks
        var merged: ArrayList<PlannerBlock>? = null
        for (index in blocks.indices) {
            val block = blocks[index]
            val pending = pendingTimes[block.id] ?: continue
            if (pending.startMinutes == block.startMinutes && pending.durationMinutes == block.durationMinutes) continue
            if (merged == null) merged = ArrayList(blocks)
            merged[index] = block.copy(startMinutes = pending.startMinutes, durationMinutes = pending.durationMinutes)
        }
        merged?.sortWith(PlannerBlockOrder)
        return merged ?: blocks
    }

    private fun showMessage(message: String) {
        snackbar = PlannerSnackbar(System.nanoTime(), message)
        changed()
    }

    private fun showSheet(value: PlannerSheet?) {
        if (sheet === value && sheetError == null) return
        sheet = value
        sheetError = null
        changed()
    }

    private fun setSheetError(message: String?) {
        if (sheetError == message) return
        sheetError = message
        changed()
    }
}

private class PendingTime(
    val startMinutes: Int,
    val durationMinutes: Int
)

private fun LocalDate.isNear(day: LocalDate): Boolean = abs(toEpochDay() - day.toEpochDay()) <= 1
