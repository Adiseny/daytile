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
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val Store = "reminders"
private const val EnabledKey = "enabled"
private const val Channel = "reminders"
private const val ExtraEpochMinute = "minute"

// One switch, one pending alarm. When reminders are on, the next block to start is
// armed; firing it notifies whatever begins at that minute and arms the block after.
// A reboot, a clock change and every write all reduce to the same `sync` call, so
// there is no per-block alarm state to drift, leak or reconcile.
class Reminders(private val context: Context) {
    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        context.getSharedPreferences(Store, Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = preferences.getBoolean(EnabledKey, false)
        set(value) = preferences.edit().putBoolean(EnabledKey, value).apply()

    suspend fun sync(repository: PlannerRepository, firedMinute: Long = -1L) {
        if (firedMinute >= 0 && enabled) notify(repository, firedMinute)
        val now = LocalDateTime.now()
        val from = maxOf(
            epochMinute(now.toLocalDate(), TimeSnapper.minuteOfDay(now.toLocalTime())),
            firedMinute
        ) + 1
        val next = if (enabled) repository.getNextBlock(dateOf(from), minuteOf(from)) else null
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = alarmIntent(next?.let { epochMinute(it.date, it.startMinutes) } ?: 0L)
        if (next == null) {
            alarms.cancel(alarm)
            alarm.cancel()
            return
        }
        val startsAt = next.date.atStartOfDay(ZoneId.systemDefault())
            .plusMinutes(next.startMinutes.toLong())
            .toInstant()
            .toEpochMilli()
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startsAt, alarm)
        } catch (_: SecurityException) {
            // Exact alarms revoked in system settings: still deliver, just not to the minute.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startsAt, alarm)
        }
    }

    private suspend fun notify(repository: PlannerRepository, minute: Long) {
        val starting = repository.getBlocksStartingAt(dateOf(minute), minuteOf(minute))
        if (starting.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Idempotent: an importance the user has since changed is left alone by the platform.
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
        starting.forEach { block ->
            manager.notify(
                block.id.toInt(),
                Notification.Builder(context, Channel)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(block.title)
                    .setContentText(
                        TimeFormatter.range(block.startMinutes, block.durationMinutes) +
                            " · " + TimeFormatter.duration(block.durationMinutes)
                    )
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun alarmIntent(minute: Long): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).putExtra(ExtraEpochMinute, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

private fun epochMinute(date: LocalDate, startMinutes: Int): Long =
    date.toEpochDay() * TimeSnapper.MinutesPerDay + startMinutes

private fun dateOf(minute: Long): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(minute, TimeSnapper.MinutesPerDay.toLong()))

private fun minuteOf(minute: Long): Int =
    Math.floorMod(minute, TimeSnapper.MinutesPerDay.toLong()).toInt()
