package com.forge.os.domain.alarms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.cron.TaskType
import com.forge.os.domain.memory.MemoryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: ForgeAlarmScheduler
    @Inject lateinit var toolRegistry: ToolRegistry
    @Inject lateinit var sandboxManager: SandboxManager
    @Inject lateinit var memoryManager: MemoryManager
    @Inject lateinit var sessionLog: AlarmSessionLog
    @Inject lateinit var cronManager: CronManager
    @Inject lateinit var backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val item = repository.find(id) ?: return
        if (!item.enabled) return

        Timber.i("AlarmReceiver: firing '${item.label}' (${item.action})")
        postNotification(context, item)

        val pending = goAsync()
        val started = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            var success = true
            var output = ""
            var err: String? = null
            try {
                when (item.action) {
                    AlarmAction.NOTIFY -> { output = "Notified: ${item.label}" }
                    AlarmAction.RUN_TOOL -> { output = runTool(item.payload) }
                    AlarmAction.RUN_PYTHON -> {
                        val r = sandboxManager.executePython(item.payload)
                        r.fold(
                            onSuccess = { output = it.take(2000) },
                            onFailure = { success = false; err = it.message; output = "python failed: ${it.message}" }
                        )
                    }
                    AlarmAction.PROMPT_AGENT -> {
                        // Phase R fix — PROMPT_AGENT used to silently log to memory
                        // and never actually run the agent. Now we hand the prompt
                        // off to the existing CronManager runner, which spins up a
                        // headless agent run in the background, captures its output,
                        // and notifies on completion.
                        memoryManager.logEvent(
                            role = "alarm",
                            content = "Firing prompt: ${item.payload.take(400)}",
                            tags = listOf("alarm", item.id),
                        )
                        val r = runCatching {
                            val spec = cronManager.resolveSpec(item.overrideProvider, item.overrideModel)
                            cronManager.runPromptOnce(
                                label = "alarm:${item.label}",
                                prompt = item.payload,
                                spec = spec
                            )
                        }
                        r.fold(
                            onSuccess = { output = it.take(2000) },
                            onFailure = { success = false; err = it.message; output = "prompt failed: ${it.message}" }
                        )
                    }
                }

                if (item.repeatIntervalMs > 0) {
                    // Use the original triggerAt as the base so repeated alarms
                    // stay aligned to their schedule even if execution was late.
                    val nextTrigger = item.triggerAt + item.repeatIntervalMs
                    val next = item.copy(
                        triggerAt = if (nextTrigger > System.currentTimeMillis()) nextTrigger
                                    else System.currentTimeMillis() + item.repeatIntervalMs
                    )
                    repository.upsert(next)
                    scheduler.scheduleExact(next)
                } else {
                    val done = item.copy(enabled = false)
                    repository.upsert(done)
                }
            } catch (t: Throwable) {
                success = false
                err = t.message
                output = "alarm failed: ${t.message}"
            } finally {
                runCatching {
                    sessionLog.append(
                        AlarmSession(
                            id = "as_${System.currentTimeMillis()}",
                            alarmId = item.id,
                            label = item.label,
                            action = item.action,
                            firedAtMs = started,
                            durationMs = System.currentTimeMillis() - started,
                            success = success,
                            output = output.take(4000),
                            error = err,
                        )
                    )
                }
                runCatching {
                    val finished = System.currentTimeMillis()
                    val duration = finished - started
                    backgroundLog.addLog(
                        com.forge.os.domain.debug.BackgroundTaskLog(
                            id = "alarm_${finished}",
                            source = com.forge.os.domain.debug.TaskSource.ALARM,
                            label = item.label,
                            success = success,
                            output = output,
                            error = err,
                            durationMs = duration,
                            timestamp = finished
                        )
                    )
                }
                pending.finish()
            }
        }
    }

    private suspend fun runTool(payload: String): String {
        val parts = payload.split("|", limit = 2)
        val tool = parts.firstOrNull()?.trim().orEmpty()
        val args = parts.getOrNull(1) ?: "{}"
        if (tool.isBlank()) return "no tool name in payload"
        val r = toolRegistry.dispatch(tool, args, "alarm_${System.currentTimeMillis()}")
        return "${if (r.isError) "❌" else "✅"} ${r.toolName}: ${r.output.take(1000)}"
    }

    private fun postNotification(context: Context, item: AlarmItem) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, CHANNEL_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ ${item.label}")
                .setContentText(item.payload.take(120).ifBlank { "Alarm triggered" })
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(item.id.hashCode(), notif)
        }.onFailure { Timber.w(it, "AlarmReceiver: notify failed") }
    }

    companion object {
        const val ACTION = "com.forge.os.action.ALARM_FIRED"
        const val EXTRA_ID = "alarm_id"
        const val CHANNEL_ALARM = "forge_alarms"
    }
}
