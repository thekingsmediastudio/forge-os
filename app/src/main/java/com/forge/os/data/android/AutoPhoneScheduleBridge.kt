package com.forge.os.data.android

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Forge OS's [CronManager] / [AlarmScheduler] to the Forge AutoPhone
 * schedule lifecycle AIDL calls.
 *
 * When a cron job or alarm is tagged as an AutoPhone plan, the job executor
 * should call [onPlanStarted] before beginning and [onPlanFinished] when done.
 * This causes AutoPhone's Schedules screen to show a live "Running…" indicator
 * on the matching card and update the last-run timestamp automatically.
 *
 * Usage in CronExecutionWorker:
 *
 *   ```kotlin
 *   scheduleBridge.onPlanStarted(job.id, job.name)
 *   try {
 *       val result = agent.run(job.payload)
 *       scheduleBridge.onPlanFinished(job.id, ok = true, result)
 *   } catch (e: Exception) {
 *       scheduleBridge.onPlanFinished(job.id, ok = false, e.message ?: "Error")
 *   }
 *   ```
 */
@Singleton
class AutoPhoneScheduleBridge @Inject constructor(
    private val autoPhone: AutoPhoneConnection,
) {
    /**
     * Call when a schedule plan starts executing.
     *
     * @param scheduleId   Any stable ID that maps to [Schedule.id] on the AutoPhone side.
     *                     Convention: use the cron job's id or alarm id.
     * @param planSummary  Short description shown on the "Running…" chip (≤ 60 chars).
     */
    fun onPlanStarted(scheduleId: String, planSummary: String) {
        Timber.i("AutoPhone schedule started: $scheduleId")
        if (autoPhone.isConnected) {
            autoPhone.notifyScheduleStarted(scheduleId, planSummary.take(60))
        }
    }

    /**
     * Call when a schedule plan finishes (success or failure).
     *
     * @param scheduleId  Same id passed to [onPlanStarted].
     * @param ok          true if the plan completed without error.
     * @param result      Human-readable outcome or error message (≤ 120 chars).
     */
    fun onPlanFinished(scheduleId: String, ok: Boolean, result: String) {
        Timber.i("AutoPhone schedule finished: $scheduleId ok=$ok")
        if (autoPhone.isConnected) {
            autoPhone.notifyScheduleCompleted(scheduleId, ok, result.take(120))
        }
    }
}
