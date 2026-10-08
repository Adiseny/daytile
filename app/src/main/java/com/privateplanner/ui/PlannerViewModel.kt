package com.privateplanner.ui

import android.util.LongSparseArray
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

// The sheet that is open: its kind, and the minute a new block starts at or the id of the
// block to rename or act on. One kind with a number, not a type each: a type is a class.
class PlannerSheet(val kind: Int, val value: Long) {
    companion object {
        const val Create = 0
        const val Rename = 1
        const val Actions = 2
        const val Date = 3
    }
}

// The same each time, so asking for the date sheet while it is open changes nothing.
private val DateJump = PlannerSheet(PlannerSheet.Date, 0)

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

    // Whether the week holding the selected day is on show in the day's place. Read by the
    // worker as the day is.
    @Volatile
    var week = false
        private set

    // The day the week was come into from, which leaving the week returns to. Acting on
    // another of its days selects that day and leaves this one as it is.
    var weekHome: LocalDate = selectedDate
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

    // The selected day and its neighbours, for an instant swipe; with the week on show, that
    // week and the one either side of it.
    private val dayCache = HashMap<LocalDate, List<PlannerBlock>>()
    private val loadingDays = HashSet<LocalDate>()
    private val loadingWeeks = HashSet<LocalDate>()

    // A neighbouring day as it is cached, for a day swipe to show before it is selected.
    fun cachedBlocks(date: LocalDate): List<PlannerBlock>? = dayCache[date]

    // Moves and resizes that are on screen but not yet saved, by block, and likewise blocks
    // on their way to another day, each as it will be there.
    private val pendingTimes = LongSparseArray<PendingTime>()
    private val pendingDays = LongSparseArray<PlannerBlock>()
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
        if (week) return loadWeeks(date)
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

    // The week on show, then the one after and the one before, each in one read. A week
    // with every day cached is not read again.
    private fun loadWeeks(date: LocalDate) {
        val monday = date.weekStart()
        for (index in 0..2) {
            val first = monday.plusWeeks(if (index == 2) -1 else index.toLong())
            var cached = true
            for (day in 0..6) if (!dayCache.containsKey(first.plusDays(day.toLong()))) cached = false
            if (cached || !loadingWeeks.add(first)) continue
            Worker.execute {
                val read = if (keeps(first)) repository.getBlocksForWeek(first) else null
                onMain {
                    loadingWeeks.remove(first)
                    if (read != null) publishWeek(first, read) else if (keeps(first)) load(selectedDate)
                }
            }
        }
    }

    // What is cached and read: the selected day and its neighbours or, with the week on
    // show, that week and the one either side of it.
    private fun keeps(date: LocalDate): Boolean {
        val selected = selectedDate
        if (!week) return date.isNear(selected)
        val days = date.toEpochDay() - selected.weekStart().toEpochDay()
        return days >= -7 && days < 14
    }

    // A read usually confirms what is already shown (the move made ahead of its save, the
    // cached day), and then the list on screen is kept, so nothing is drawn again. Answers
    // whether the screen has something new to show, and says so itself unless told to be quiet.
    private fun publish(date: LocalDate, read: List<PlannerBlock>, quiet: Boolean = false): Boolean {
        if (!keeps(date)) return false
        val selected = date == selectedDate
        val merged = withPending(date, read)
        val before = if (selected) blocks else dayCache[date]
        val shown = if (before != null && merged == before) before else merged
        dayCache[date] = shown
        if (shown === before) return false
        if (selected) blocks = shown else if (!week) return false
        if (!quiet) changed()
        return true
    }

    // A week's rows are in the order of its days, so each day is one run of them.
    private fun publishWeek(first: LocalDate, rows: List<PlannerBlock>) {
        var index = 0
        var different = false
        for (offset in 0..6) {
            val day = first.plusDays(offset.toLong())
            var end = index
            while (end < rows.size && rows[end].date == day) end++
            val list: List<PlannerBlock> = if (end == index) Collections.emptyList() else ArrayList(rows.subList(index, end))
            index = end
            if (publish(day, list, quiet = true)) different = true
        }
        if (different) changed()
    }

    // The cached day again, with whatever is now pending.
    private fun republish(date: LocalDate, quiet: Boolean = false): Boolean =
        dayCache[date]?.let { publish(date, it, quiet) } ?: false

    // The day as the write now ending left it, for what the write's end has to find in it.
    private var written: List<PlannerBlock> = Collections.emptyList()

    // A write, then its day as the write left it, in one trip to the worker.
    private fun write(date: LocalDate, action: Supplier<Int>, done: Consumer<Int>) {
        Worker.execute {
            val result = action.get()
            val read = repository.getBlocksForDate(date)
            onMain {
                written = read
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

    // The same day of another week.
    fun shiftWeek(weeks: Long) {
        weekHome = weekHome.plusWeeks(weeks)
        setDate(selectedDate.plusWeeks(weeks))
    }

    fun jumpTo(date: LocalDate) {
        weekHome = date
        setDate(date)
        dismissSheet()
    }

    // The week holding the selected day in the day's place, or the day it was come into
    // from again.
    fun showWeek(on: Boolean) {
        if (week == on) return
        if (on) weekHome = selectedDate
        week = on
        if (!on) {
            selectedDate = weekHome
            blocks = dayCache[weekHome] ?: Collections.emptyList()
            dayCache.keys.retainAll { keeps(it) }
        }
        changed()
        load(selectedDate)
    }

    // One of the week's days becomes the selected one, the week staying on show: a sheet
    // is about to open for it, or the week to open out into it.
    fun selectDay(date: LocalDate) {
        setDate(date)
    }

    // Out of the week and into one of its days, by choice.
    fun openDay(date: LocalDate) {
        weekHome = date
        showWeek(false)
    }

    fun openCreate(startMinutes: Int) {
        showSheet(PlannerSheet(PlannerSheet.Create, TimeSnapper.floorToValidStart(startMinutes).toLong()))
    }

    fun openActions(blockId: Long) {
        showSheet(PlannerSheet(PlannerSheet.Actions, blockId))
    }

    fun openRename(blockId: Long) {
        showSheet(PlannerSheet(PlannerSheet.Rename, blockId))
    }

    fun openDateJump() {
        showSheet(DateJump)
    }

    fun dismissSheet() {
        showSheet(null)
    }

    // `again` is the keyboard's key, which adds the block and leaves the sheet for the
    // next one, starting where this one ends: a morning is written down in a row.
    fun createBlock(title: String, again: Boolean = false) {
        val current = sheet?.takeIf { it.kind == PlannerSheet.Create } ?: return
        val date = selectedDate
        val start = current.value.toInt()
        sheetWrite(current, title, if (again) start else -1) { repository.createBlock(date, start, title) }
    }

    fun renameBlock(title: String) {
        val current = sheet?.takeIf { it.kind == PlannerSheet.Rename } ?: return
        // A rename sheet is open only over its block's day.
        val date = selectedDate
        sheetWrite(current, title, -1) { repository.updateTitle(date, current.value, title) }
    }

    // The sheet for the block after the one just added at `start`, or none where the day
    // has run out.
    private fun nextInRow(start: Int): PlannerSheet? {
        var end = -1
        var newest = -1L
        for (block in written) {
            if (block.startMinutes == start && block.id > newest) {
                newest = block.id
                end = block.endMinutes
            }
        }
        if (start < 0 || end < 0 || end > TimeSnapper.MinutesPerDay - TimeSnapper.MinimumDurationMinutes) return null
        return PlannerSheet(PlannerSheet.Create, end.toLong())
    }

    // A sheet's write keeps running if the sheet goes away meanwhile: the user asked for
    // it, so a late failure surfaces as a message instead of a silently lost write.
    private fun sheetWrite(current: PlannerSheet, title: String, rowStart: Int, action: Supplier<Int>) {
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
                if (error == null) showSheet(nextInRow(rowStart)) else setSheetError(error)
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

    // Blocks of the selected day that were lifted together, by the same number of minutes:
    // on screen at once and saved behind in one write, all of them or none.
    fun shiftBlocks(ids: LongArray, deltaMinutes: Int) {
        val date = selectedDate
        val pending = arrayOfNulls<PendingTime>(ids.size)
        for (index in ids.indices) {
            val block = block(ids[index]) ?: continue
            pending[index] = PendingTime(block.startMinutes + deltaMinutes, block.durationMinutes).also { pendingTimes.put(block.id, it) }
        }
        republish(date)
        write(date, { repository.shiftBlocks(date, ids, deltaMinutes) }) { result ->
            // A later change to one of them has taken that one over.
            for (index in ids.indices) if (pendingTimes[ids[index]] === pending[index]) pendingTimes.remove(ids[index])
            if (result == PlannerWriteResult.RejectedOverlap) {
                showMessage("No space there")
            } else if (result != PlannerWriteResult.Success) {
                showMessage("Could not save")
            }
        }
    }

    // From the week, where the block is of any of its days.
    fun resizeBlock(block: PlannerBlock, durationMinutes: Int) {
        val duration = TimeSnapper.clampDuration(block.startMinutes, TimeSnapper.snapDurationToNearest(durationMinutes))
        if (duration != block.durationMinutes) setTime(block, block.startMinutes, duration)
    }

    // To another time of its day or to another day, from the week: on screen at once and
    // saved behind, as a move within the day is.
    fun moveBlock(block: PlannerBlock, toDate: LocalDate, startMinutes: Int): Boolean {
        val start = TimeSnapper.clampStart(TimeSnapper.floorToSnap(startMinutes), block.durationMinutes)
        if (toDate == block.date) {
            if (start != block.startMinutes) setTime(block, start, block.durationMinutes)
            return true
        }
        val moved = block.copy(date = toDate, startMinutes = start)
        pendingTimes.remove(block.id)
        pendingDays.put(block.id, moved)
        val fromChanged = republish(block.date, quiet = true)
        val toChanged = republish(toDate, quiet = true)
        if (fromChanged || toChanged) changed()
        Worker.execute {
            val result = repository.moveToDay(block.date, block.id, toDate, start)
            val from = repository.getBlocksForDate(block.date)
            val to = repository.getBlocksForDate(toDate)
            onMain {
                // A later move of the same block has taken over, and its result settles both.
                if (pendingDays[block.id] === moved) pendingDays.remove(block.id)
                publish(block.date, from)
                publish(toDate, to)
                if (result == PlannerWriteResult.RejectedOverlap) {
                    showMessage("No space there")
                } else if (result != PlannerWriteResult.Success) {
                    showMessage("Could not save")
                }
            }
        }
        return true
    }

    // On screen at once and saved behind. A save that fails leaves the day as stored.
    private fun setTime(block: PlannerBlock, startMinutes: Int, durationMinutes: Int) {
        val pending = PendingTime(startMinutes, durationMinutes)
        pendingTimes.put(block.id, pending)
        republish(block.date)
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
        selectedDate = date
        dayCache.keys.retainAll { keeps(it) }
        blocks = dayCache[date] ?: Collections.emptyList()
        changed()
        load(date)
    }

    private fun block(blockId: Long): PlannerBlock? = blocks.firstOrNull { it.id == blockId }

    // A day as read, with what is on screen ahead of its save: a block bound for another
    // day gone from this one and standing on that one, and moves and resizes within the day.
    private fun withPending(date: LocalDate, read: List<PlannerBlock>): List<PlannerBlock> {
        if (pendingDays.size() == 0 && pendingTimes.size() == 0) return read
        var merged: ArrayList<PlannerBlock>? = null
        var moved = false
        for (index in 0 until pendingDays.size()) {
            val bound = pendingDays.valueAt(index)
            val at = (merged ?: read).indexOfFirst { it.id == bound.id }
            // Here and staying, or neither here nor coming.
            if ((at >= 0) == (bound.date == date)) continue
            val changed = merged ?: ArrayList(read).also { merged = it }
            if (at >= 0) {
                changed.removeAt(at)
            } else {
                changed.add(bound)
                moved = true
            }
        }
        val blocks = merged ?: read
        for (index in blocks.indices) {
            val block = blocks[index]
            val pending = pendingTimes[block.id] ?: continue
            if (pending.startMinutes == block.startMinutes && pending.durationMinutes == block.durationMinutes) continue
            if (merged == null) merged = ArrayList(blocks)
            if (pending.startMinutes != block.startMinutes) moved = true
            merged[index] = block.copy(startMinutes = pending.startMinutes, durationMinutes = pending.durationMinutes)
        }
        if (moved) merged?.sortWith(PlannerBlockOrder)
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

// The Monday of a day's week.
internal fun LocalDate.weekStart(): LocalDate = minusDays((dayOfWeek.value - 1).toLong())
