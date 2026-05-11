package com.forge.os.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forge.os.domain.cron.CronManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that wakes up every 15 minutes (the WorkManager floor for
 * periodic work) and runs every cron job that is currently due.
 *
 * Job-level scheduling granularity is enforced by [com.forge.os.domain.cron.CronScheduler];
 * jobs can request finer schedules but actual fire times are aligned to the
 * 15-minute worker tick.  This is acceptable because the minimum allowed
 * job interval is 5 minutes (see [com.forge.os.domain.config.CronSettings]).
 */
@HiltWorker
class CronExecutionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cronManager: CronManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val executions = cronManager.runDueJobs()
            if (executions.isNotEmpty()) {
                val ok = executions.count { it.success }
                Timber.i("CronExecutionWorker: ran ${executions.size} job(s), $ok succeeded")
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CronExecutionWorker: failed")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "forge_cron_execution"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CronExecutionWorker>(15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("CronExecutionWorker: scheduled (every 15 minutes)")
        }
    }
}
