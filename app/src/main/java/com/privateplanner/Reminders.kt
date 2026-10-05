package com.privateplanner

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.privateplanner.data.PlannerBlockDao
import com.privateplanner.domain.BlockLayouts
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

// A channel's sound is fixed when it is created, so the two moments need two
// channels rather than one. That also lets the warning be silenced on its own
// while the start stays audible.
private const val UpcomingChannel = "reminders.upcoming"
private const val StartChannel = "reminders.start"
private const val ExtraEpochMinute = "minute"

// Enough warning to finish what you are doing and get to the next thing.
private const val LeadMinutes = 5

// No real event minute can collide with either: one means "nothing to arm", the
// other "we have not looked yet".
private const val NoAlarm = Long.MIN_VALUE
private const val UnknownAlarm = Long.MAX_VALUE

// One switch, one pending alarm. The switch is the receiver's own state: the receiver
// exists for reminders alone, so it is enabled only while they are on, and the system
// keeps that across restarts and updates with no file of the planner's. Off, as it is
// until someone turns it on, a reboot or a clock change does not start the process to
// find nothing to do. A block has two moments worth a notification: the warning,
// LeadMinutes before it starts, and the start itself. Reminders arm the nearer of those
// two moments across all blocks; firing it posts whatever is due at that minute and arms
// the moment after. A reboot, a clock change and every write all reduce to the same
// `sync` call, so there is no per-block alarm state to drift, leak or reconcile. Apart
// from the switch itself, everything here runs on the worker thread, one sync at a time:
// each reads the planner's blocks as its turn comes, so the last always reflects the
// latest write.
class Reminders(private val context: Context, private val blocks: PlannerBlockDao) {
    // Notifications outlive the process, so this starts pessimistic: one query settles
    // it, and afterwards writes skip the notification service entirely.
    private var mayBeShowing = true

    // The intent never varies, so this is one ActivityManager round trip per process
    // rather than one per posting sync.
    private var open: PendingIntent? = null

    // Alarms outlive the process, so like `mayBeShowing` this starts pessimistic:
    // the first sync settles it, and afterwards a write that changes nothing costs
    // no binder traffic at all. Anything that clears alarms behind our back (reboot,
    // update, force-stop) also kills the process, so it cannot go stale. The instant
    // is kept too: a time-zone change moves an unchanged event's wall-clock minute to
    // another instant, which must be re-armed.
    private var armedFor = UnknownAlarm
    private var armedAt = 0L

    // Channels persist across restarts, so declaring them is a once-per-process
    // errand rather than something every post should pay for.
    private var channelsReady = false

    // The switch as this process knows it: asked of the system once, by the worker as the
    // process starts, and afterwards changed here at once and told to the system by the
    // sync that follows. Whoever asks first also brings over an earlier release's switch,
    // so that none can see the receiver before it has been.
    @Volatile
    private var switch: Boolean? = null

    // What the system was last told. The first sync in a process restates it, which also
    // settles a receiver nobody has switched either way; later ones know.
    private var receiverOn: Boolean? = null

    // Once switching off has cleared persistent alarms and notifications, planner writes
    // need no task, clock read or system call until the switch changes again. Read from
    // whichever thread asks for a sync.
    @Volatile
    private var settledOff = false

    var enabled: Boolean
        get() = switch ?: synchronized(this) {
            switch ?: run {
                if (File(context.dataDir, "shared_prefs").exists()) context.leaveEarlierReleases()
                (context.packageManager.getComponentEnabledSetting(context.reminderReceiver()) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED).also { switch = it }
            }
        }
        set(value) = synchronized(this) {
            settledOff = false
            switch = value
        }

    // Fire and forget: the caller, usually a write the user is waiting on, carries on at
    // once and the reminder follows on the worker thread, behind what is already queued
    // there. The work is done even if the screen has gone by then.
    fun syncSoon(firedMinute: Long = -1L, clockChanged: Boolean = false, then: Runnable? = null) {
        if (settledOff && !enabled) {
            then?.run()
            return
        }
        Worker.execute {
            try {
                sync(firedMinute, clockChanged)
            } finally {
                then?.run()
            }
        }
    }

    // Everything below is binder traffic and disk, so it stays on the worker thread.
    fun sync(firedMinute: Long = -1L, clockChanged: Boolean = false) {
        val on = enabled
        if (!on && settledOff) return
        settledOff = false
        if (receiverOn != on) {
            context.switchReminderReceiver(on)
            receiverOn = on
        }
        val nowMinute = Math.floorDiv(TimeSnapper.localNowMillis(), TimeSnapper.MillisPerMinute)
        if (firedMinute >= 0 || mayBeShowing) show(on, nowMinute, firedMinute, clockChanged)

        val after = maxOf(nowMinute, firedMinute)
        val event = if (on) nextEvent(after) else NoAlarm
        val at = if (event == NoAlarm) 0L else millisAt(LocalDate.ofEpochDay(dayOf(event)), minuteOf(event))
        // Most writes (a rename, an edit on another day) leave the next moment alone,
        // and with reminders off there is never anything to arm. Both land here, and
        // neither reaches a system service.
        if (event == armedFor && at == armedAt) {
            settledOff = !on
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (event == NoAlarm) {
            // Nothing to arm, so only touch the pending intent if one already exists.
            alarmIntent(0L, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            armedFor = NoAlarm
            armedAt = 0L
            settledOff = !on
            return
        }
        val alarm = alarmIntent(event, PendingIntent.FLAG_UPDATE_CURRENT)!!
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        } catch (_: SecurityException) {
            // Exact alarms revoked in system settings: still deliver, just not to the minute.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        }
        armedFor = event
        armedAt = at
    }

    // The first block after `after` owns the nearest moment: its warning if that has
    // not passed, otherwise its start. Only when the warning has passed can a later
    // block's warning still land first, which is the one extra lookup.
    private fun nextEvent(after: Long): Long {
        val first = startAfter(after) ?: return NoAlarm
        if (first - LeadMinutes > after) return first - LeadMinutes
        val later = startAfter(after + LeadMinutes) ?: return first
        return minOf(first, later - LeadMinutes)
    }

    // The first block starting strictly after `minute`, as an epoch minute.
    private fun startAfter(minute: Long): Long? = blocks.getNextStart(dayOf(minute + 1), minuteOf(minute + 1))

    // What is showing is made to equal what should be showing: every block inside one of
    // its two windows, and nothing else. The countdown notification is posted under the
    // block's negated id so that both can stand at once and each is cancelled on its own
    // terms. Re-posting an id updates it in place, so a block that is moved or resized
    // corrects itself instead of going stale.
    private fun show(on: Boolean, nowMinute: Long, firedMinute: Long, clockChanged: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val due = LinkedHashMap<Int, PlannerBlock>(4)
        if (on && firedMinute >= 0) {
            blocks.getBlocksStartingAt(dayOf(firedMinute), minuteOf(firedMinute)).forEach { due[it.id.toInt()] = it }
            val warned = firedMinute + LeadMinutes
            blocks.getBlocksStartingAt(dayOf(warned), minuteOf(warned)).forEach { due[-it.id.toInt()] = it }
        }
        val today = dayOf(nowMinute)
        val active = manager.activeNotifications
        active.forEach { posted ->
            val upcoming = posted.id < 0
            val id = abs(posted.id).toLong()
            // A block is stored under its day. One with a reminder still due is today's or,
            // warned of in the minutes before midnight, tomorrow's.
            val block = if (!on) null else blocks.getBlock(today, id) ?: if (upcoming) blocks.getBlock(today + 1, id) else null
            if (block != null && showsAt(block, nowMinute, upcoming)) {
                due[posted.id] = block
            } else {
                manager.cancel(posted.id)
            }
        }
        mayBeShowing = due.isNotEmpty()
        if (due.isEmpty()) return

        if (!channelsReady) {
            // One query rather than a delete per legacy channel and a create in every
            // process: after the first run there is nothing to change.
            val ids = manager.notificationChannels.map { it.id }
            if (ids.size != 2 || UpcomingChannel !in ids || StartChannel !in ids) {
                // The channels these two replaced, not left behind in system settings.
                manager.deleteNotificationChannel("reminders")
                manager.deleteNotificationChannel("reminders.2")
                manager.createNotificationChannels(
                    listOf(
                        channel(UpcomingChannel, "Coming up", "reminder_upcoming"),
                        channel(StartChannel, "Starting now", "reminder_start")
                    )
                )
            }
            channelsReady = true
        }
        val postedAt = System.currentTimeMillis()
        // A tile's shade follows its column among overlapping blocks, so the accent comes
        // from the same layout the timeline draws.
        val layouts = HashMap<LocalDate, BlockLayouts>(2)
        due.forEach { (id, block) ->
            val upcoming = id < 0
            val until = millisAt(
                block.date,
                if (upcoming) block.startMinutes else block.endMinutes
            )
            val remaining = until - postedAt
            if (remaining <= 0) return@forEach
            val column = layouts.getOrPut(block.date) {
                OverlapLayoutCalculator.calculate(blocks.getBlocksForDate(block.date.toEpochDay()))
            }[block.id]?.columnIndex ?: 0
            val length = " · " + TimeFormatter.duration(block.durationMinutes)
            val text = if (upcoming) {
                "Starts " + TimeFormatter.time(block.startMinutes) + length
            } else {
                TimeFormatter.range(block.startMinutes, block.durationMinutes) + length
            }
            val colour = blockBackgroundArgb(block.startMinutes, column).toInt()
            val channel = if (upcoming) UpcomingChannel else StartChannel
            val previous = active.firstOrNull { it.id == id }?.notification
            // The system owns the running countdown. An unrelated write needs neither
            // a new Notification nor another binder call to post the same contents.
            // Clock changes also reset the timeout: Android expires it by elapsed time.
            if (!clockChanged && previous != null && previous.channelId == channel && previous.`when` == until &&
                previous.color == colour && previous.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == block.title &&
                previous.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text
            ) return@forEach
            manager.notify(
                id,
                Notification.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(block.title)
                    .setContentText(text)
                    // Countdown to the start, then to the end, ticked by the system: no
                    // wakeups, no redraw work, nothing for the app to keep alive, and the
                    // system withdraws each one as its moment arrives.
                    .setWhen(until)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(remaining)
                    .setColor(colour)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(
                        open ?: PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        ).also { open = it }
                    )
                    .setAutoCancel(true)
                    // Corrections must not buzz again; only the first post alerts.
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }
    }

    // Addressed by name rather than by id: a channel stores its sound URI forever,
    // so a numeric id baked into one breaks the moment resource ids shift. The
    // resource shrinker retains these dynamically loaded sounds via raw/keep.xml.
    private fun channel(id: String, name: String, sound: String) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                Uri.parse("android.resource://${context.packageName}/raw/$sound"),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }

    private fun alarmIntent(minute: Long, flags: Int): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).putExtra(ExtraEpochMinute, minute),
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// The armed alarm, plus every event that can invalidate it: a reboot or an app
// update clears alarms, and a clock or timezone change moves the wall-clock target.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PlannerApp ?: return
        val fired = if (intent.action == null) intent.getLongExtra(ExtraEpochMinute, -1L) else -1L
        val pending = goAsync()
        app.reminders.syncSoon(fired, intent.action == Intent.ACTION_TIME_CHANGED) { pending.finish() }
    }
}

private fun Context.reminderReceiver() = ComponentName(this, ReminderReceiver::class.java)

private fun Context.switchReminderReceiver(on: Boolean) = packageManager.setComponentEnabledSetting(
    reminderReceiver(),
    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
    PackageManager.DONT_KILL_APP
)

// What releases up to 1.4.0 and their libraries kept beside the database, each a block of
// storage or more: the reminders switch in a settings file, which is handed to the
// receiver, the record of the splash, two notes of an installed profile and Room's lock.
// The folders go too, where that leaves them empty, and with the settings folder goes the
// sign that any of this is still to do: every such release made one, and nothing here does.
private fun Context.leaveEarlierReleases() {
    val settings = getSharedPreferences("reminders", Context.MODE_PRIVATE)
    if (settings.contains("enabled")) switchReminderReceiver(settings.getBoolean("enabled", false))
    deleteSharedPreferences("reminders")
    deleteSharedPreferences("launch")
    for (left in arrayOf(
        "shared_prefs",
        "cache/private_planner.db.lck",
        "files/profileInstalled",
        "files/profileinstaller_profileWrittenFor_lastUpdateTime.dat",
        "files"
    )) File(dataDir, left).delete()
}

internal fun Context.postNotificationsGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

// One reminder stands for "this block is imminent", the other for "this block is
// running", and the start is the seam where the first hands over to the second.
private fun showsAt(block: PlannerBlock, nowMinute: Long, upcoming: Boolean): Boolean {
    val start = epochMinute(block.date, block.startMinutes)
    return if (upcoming) {
        nowMinute >= start - LeadMinutes && nowMinute < start
    } else {
        nowMinute >= start && nowMinute < epochMinute(block.date, block.endMinutes)
    }
}

// Minutes are wall-clock minutes, so they are added before the zone is applied: on a
// daylight-saving day, elapsed minutes from midnight land an hour off after the change.
// A minute inside a skipped hour moves forward by the gap. Internal for tests.
internal fun millisAt(date: LocalDate, minutes: Int): Long =
    date.atStartOfDay()
        .plusMinutes(minutes.toLong())
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

private fun epochMinute(date: LocalDate, startMinutes: Int): Long =
    date.toEpochDay() * TimeSnapper.MinutesPerDay + startMinutes

private fun dayOf(minute: Long): Long = Math.floorDiv(minute, TimeSnapper.MinutesPerDay.toLong())

private fun minuteOf(minute: Long): Int =
    Math.floorMod(minute, TimeSnapper.MinutesPerDay.toLong()).toInt()
