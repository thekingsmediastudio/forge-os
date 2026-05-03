package com.forge.os.domain.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — schedules a periodic [ProactiveWorker] that, when the
 * [AgentControlPlane.PROACTIVE_SUGGEST] capability is ON, looks at recent
 * activity (browser history, recent project paths, calendar of in-app events)
 * and may post a single gentle suggestion notification with action buttons
 * (e.g. "serve this folder", "open last URL on PC").
 *
 * The worker is a no-op while the capability is OFF; turning the capability
 * on at any time begins surfacing suggestions on the next 30-minute tick.
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
) {
    fun ensureScheduled() {
        // Always enqueue; the worker checks the flag itself. This way
        // toggling the flag does not require restarting WorkManager.
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isCapabilityOn(): Boolean = controlPlane.isEnabled(AgentControlPlane.PROACTIVE_SUGGEST)

    companion object {
        const val WORK_NAME = "forge_proactive"
        const val WORK_TAG  = "forge_proactive"
    }
}
