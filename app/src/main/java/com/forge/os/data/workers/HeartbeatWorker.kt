package com.forge.os.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val heartbeatMonitor: HeartbeatMonitor
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("HeartbeatWorker: running check")
        return try {
            val status = heartbeatMonitor.checkNow()
            Timber.i("Heartbeat: ${status.overallHealth} — ${status.components.size} components checked")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "HeartbeatWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "forge_heartbeat"

        fun schedule(workManager: WorkManager, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Timber.i("Heartbeat worker scheduled (every ${intervalMinutes}min)")
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }
}
