package com.privateplanner

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val Store = "reminders"
private const val EnabledKey = "enabled"
private const val Channel = "reminders"
private const val ExtraEpochMinute = "minute"

// Enough warning to finish what you are doing and get to the next thing.
private const val LeadMinutes = 5

// One switch, one pending alarm. When reminders are on, the next block to start is
// armed; firing it shows whatever begins at that minute and arms the block after.
// A reboot, a clock change and every write all reduce to the same `sync` call, so
// there is no per-block alarm state to drift, leak or reconcile.
class Reminders(private val context: Context) {
    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        context.getSharedPreferences(Store, Context.MODE_PRIVATE)
    }

    // Notifications outlive the process, so this starts pessimistic: one query settles
    // it, and afterwards writes skip the notification service entirely.
    @Volatile
    private var mayBeShowing = true

    // Anything that clears alarms behind our back (reboot, update, force-stop) also
    // kills the process, so remembering the armed minute cannot go stale.
    @Volatile
    private var armedFor = Long.MIN_VALUE

    var enabled: Boolean
        get() = preferences.getBoolean(EnabledKey, false)
        set(value) = preferences.edit().putBoolean(EnabledKey, value).apply()

    // Callers are writes running on the main thread; everything below is binder
    // traffic and disk, so none of it belongs on the caller's thread.
    suspend fun sync(
        repository: PlannerRepository,
        firedMinute: Long = -1L
    ) = withContext(Dispatchers.Default) {
        val on = enabled
        val now = LocalDateTime.now()
        val nowMinute = epochMinute(now.toLocalDate(), TimeSnapper.minuteOfDay(now.toLocalTime()))
        if (firedMinute >= 0 || mayBeShowing) show(repository, on, nowMinute, firedMinute)

        val from = maxOf(nowMinute, firedMinute) + 1
        val next = if (on) repository.getNextBlock(dateOf(from), minuteOf(from)) else null
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            // Nothing to arm, so only touch the pending intent if one already exists.
            alarmIntent(0L, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            armedFor = Long.MIN_VALUE
            return@withContext
        }
        // Most writes (a rename, an edit on another day) leave the next block alone.
        val target = epochMinute(next.date, next.startMinutes)
        if (target == armedFor) return@withContext
        val alarm = alarmIntent(target, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val warnAt = millisAt(next.date, next.startMinutes) - LeadMinutes * 60_000L
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warnAt, alarm)
        } catch (_: SecurityException) {
            // Exact alarms revoked in system settings: still deliver, just not to the minute.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warnAt, alarm)
        }
        armedFor = target
    }

    // What is showing is made to equal what should be showing: every block inside its
    // reminder window, and nothing else. Re-posting an id updates it in place, so a
    // block that is moved or resized corrects itself instead of going stale.
    private suspend fun show(
        repository: PlannerRepository,
        on: Boolean,
        nowMinute: Long,
        firedMinute: Long
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val due = LinkedHashMap<Int, PlannerBlock>(2)
        if (on && firedMinute >= 0) {
            repository.getBlocksStartingAt(dateOf(firedMinute), minuteOf(firedMinute))
                .forEach { due[it.id.toInt()] = it }
        }
        manager.activeNotifications.forEach { posted ->
            val block = if (on) repository.getBlock(posted.id.toLong()) else null
            if (block != null && showsAt(block, nowMinute)) {
                due[posted.id] = block
            } else {
                manager.cancel(posted.id)
            }
        }
        mayBeShowing = due.isNotEmpty()
        if (due.isEmpty()) return

        manager.createNotificationChannel(
            NotificationChannel(Channel, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val postedAt = System.currentTimeMillis()
        due.forEach { (id, block) ->
            val endsAt = millisAt(block.date, block.endMinutes)
            val remaining = endsAt - postedAt
            if (remaining <= 0) return@forEach
            manager.notify(
                id,
                Notification.Builder(context, Channel)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(block.title)
                    .setContentText(
                        TimeFormatter.range(block.startMinutes, block.durationMinutes) +
                            " · " + TimeFormatter.duration(block.durationMinutes)
                    )
                    // Countdown to the end, ticked by the system: no wakeups, no redraw
                    // work, nothing for the app to keep alive, and the system withdraws
                    // it when the block is over.
                    .setWhen(endsAt)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(remaining)
                    .setColor(blockBackgroundArgb(block.startMinutes, 0).toInt())
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    // Corrections must not buzz again; only the first post alerts.
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }
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
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.reminders.sync(app.repository, fired)
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun Context.postNotificationsGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

// A reminder stands for "this block is imminent or running".
private fun showsAt(block: PlannerBlock, nowMinute: Long): Boolean =
    nowMinute >= epochMinute(block.date, block.startMinutes) - LeadMinutes &&
        nowMinute < epochMinute(block.date, block.endMinutes)

private fun millisAt(date: LocalDate, minutes: Int): Long =
    date.atStartOfDay(ZoneId.systemDefault())
        .plusMinutes(minutes.toLong())
        .toInstant()
        .toEpochMilli()

private fun epochMinute(date: LocalDate, startMinutes: Int): Long =
    date.toEpochDay() * TimeSnapper.MinutesPerDay + startMinutes

private fun dateOf(minute: Long): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(minute, TimeSnapper.MinutesPerDay.toLong()))

private fun minuteOf(minute: Long): Int =
    Math.floorMod(minute, TimeSnapper.MinutesPerDay.toLong()).toInt()
