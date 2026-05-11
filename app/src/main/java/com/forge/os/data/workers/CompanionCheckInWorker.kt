package com.forge.os.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forge.os.domain.companion.CheckInScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Phase L — periodic worker that asks [CheckInScheduler] whether any
 * proactive check-in is due and, if so, fires a single notification.
 *
 * 15-minute period (the WorkManager minimum) gives the morning check-in
 * roughly a ±15-minute landing window relative to the user's configured time.
 */
@HiltWorker
class CompanionCheckInWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: CheckInScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val report = scheduler.runOnce()
        if (report.fired > 0) {
            Timber.i("CompanionCheckIn: fired ${report.fired} of ${report.attempted}")
        } else if (report.skippedReason != null) {
            Timber.d("CompanionCheckIn: skipped — ${report.skippedReason}")
        }
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "CompanionCheckInWorker failed")
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "forge_companion_checkin"

        fun schedule(workManager: WorkManager, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<CompanionCheckInWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            Timber.i("Companion check-in worker scheduled (every ${intervalMinutes}min)")
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }
}
