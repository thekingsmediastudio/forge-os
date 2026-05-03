package com.forge.os.domain.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forge wrapper around [android.app.AlarmManager]. Schedules exact alarms
 * that fire even when the device is idle. Falls back to an inexact alarm if
 * SCHEDULE_EXACT_ALARM is not granted on API 31+.
 */
@Singleton
class ForgeAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
) {
    private val manager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleExact(item: AlarmItem) {
        if (!item.enabled || item.triggerAt <= System.currentTimeMillis()) return
        val pi = pendingIntentFor(item.id)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi)
            }
            Timber.i("ForgeAlarmScheduler: scheduled '${item.label}' at ${item.triggerAt}")
        } catch (e: SecurityException) {
            Timber.w(e, "ForgeAlarmScheduler: exact alarm denied; using inexact")
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi)
            }
        }
    }

    fun cancel(id: String) {
        runCatching { manager.cancel(pendingIntentFor(id)) }
    }

    fun addAlarm(item: AlarmItem): AlarmItem {
        repository.upsert(item)
        scheduleExact(item)
        return item
    }

    fun removeAlarm(id: String): Boolean {
        cancel(id)
        return repository.remove(id)
    }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        repository.all().filter { it.enabled && it.triggerAt > now }.forEach(::scheduleExact)
    }

    private fun pendingIntentFor(id: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
