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
import com.forge.os.domain.companion.DependencyMonitor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Phase O-2 — Nightly background job that evaluates companion usage against
 * the configured thresholds and fires a gentle nudge notification when
 * warranted. Scheduled once per day; actual threshold logic lives in
 * [DependencyMonitor.runNightlyCheck].
 *
 * Anti-dark-pattern notes:
 *  - This runs at most once per 24 h by WorkManager design.
 *  - [DependencyMonitor] enforces an additional 30-day cooldown on nudges.
 *  - No network access required (local-only data).
 */
@HiltWorker
class DependencyMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dependencyMonitor: DependencyMonitor,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Timber.d("DependencyMonitorWorker: running nightly check")
            dependencyMonitor.runNightlyCheck()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DependencyMonitorWorker: check failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "dependency_monitor"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<DependencyMonitorWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelAllWorkByTag(TAG)
        }
    }
}
