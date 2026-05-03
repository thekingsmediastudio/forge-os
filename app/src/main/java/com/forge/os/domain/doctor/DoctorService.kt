package com.forge.os.domain.doctor

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.chaquo.python.Python
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.plugins.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class CheckStatus { OK, WARN, FAIL }

@Serializable
data class DoctorCheck(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fixable: Boolean = false,
)

@Serializable
data class DoctorReport(
    val ranAt: Long,
    val checks: List<DoctorCheck>,
) {
    val hasFailures: Boolean get() = checks.any { it.status == CheckStatus.FAIL }
    val hasWarnings: Boolean get() = checks.any { it.status == CheckStatus.WARN }
}

/**
 * Foundation for the future "4G Doctor" companion app. Exposes a simple
 * check/fix contract over the core Forge OS subsystems so an external app
 * (or the agent itself) can ask "what's wrong?" and request automatic
 * remediation for well-known failure modes.
 */
@Singleton
class DoctorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val heartbeatMonitor: HeartbeatMonitor,
    private val pluginManager: PluginManager,
) {
    fun runChecks(): DoctorReport {
        val checks = listOf(
            checkWorkspace(),
            checkWorkspaceWritable(),
            checkStorage(),
            checkConfig(),
            checkPython(),
            checkPlugins(),
            checkHeartbeat(),
        )
        return DoctorReport(System.currentTimeMillis(), checks)
    }

    fun fix(id: String): DoctorCheck {
        return when (id) {
            "workspace" -> {
                initWorkspace()
                DoctorCheck(id, "Workspace", CheckStatus.OK, "Workspace directories recreated.")
            }
            "workspace_writable" -> {
                initWorkspace()
                checkWorkspaceWritable()
            }
            "plugins" -> {
                // Re-run the check; PluginManager lazily rescans its workspace dir
                // when listAllTools() is called.
                checkPlugins()
            }
            else -> DoctorCheck(id, id, CheckStatus.WARN, "No automatic fix registered.")
        }
    }

    private fun workspaceDir(): File = context.filesDir.resolve("workspace")

    private fun checkWorkspace(): DoctorCheck {
        val base = workspaceDir()
        val expected = listOf(
            "memory/daily", "memory/longterm", "memory/skills",
            "cron/queue", "cron/history", "plugins", "system",
        )
        val missing = expected.filter { !base.resolve(it).exists() }
        return if (missing.isEmpty()) {
            DoctorCheck("workspace", "Workspace layout", CheckStatus.OK,
                "All ${expected.size} core directories present.")
        } else {
            DoctorCheck("workspace", "Workspace layout", CheckStatus.FAIL,
                "Missing: ${missing.joinToString()}", fixable = true)
        }
    }

    private fun checkWorkspaceWritable(): DoctorCheck {
        return try {
            val probe = workspaceDir().resolve("system/.doctor-probe")
            probe.parentFile?.mkdirs()
            probe.writeText("ok ${System.currentTimeMillis()}")
            probe.delete()
            DoctorCheck("workspace_writable", "Workspace writable", CheckStatus.OK,
                "Filesystem write test succeeded.")
        } catch (e: Exception) {
            DoctorCheck("workspace_writable", "Workspace writable", CheckStatus.FAIL,
                "Write failed: ${e.message}", fixable = true)
        }
    }

    private fun checkStorage(): DoctorCheck {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val free = stat.availableBytes
            val total = stat.totalBytes.coerceAtLeast(1L)
            val pctFree = (100 * free / total).toInt()
            when {
                pctFree < 5 -> DoctorCheck("storage", "Device storage",
                    CheckStatus.FAIL, "$pctFree% free ($free bytes). Free up space.")
                pctFree < 15 -> DoctorCheck("storage", "Device storage",
                    CheckStatus.WARN, "$pctFree% free. Consider clearing caches.")
                else -> DoctorCheck("storage", "Device storage",
                    CheckStatus.OK, "$pctFree% free.")
            }
        } catch (e: Exception) {
            DoctorCheck("storage", "Device storage", CheckStatus.WARN,
                "Could not read storage: ${e.message}")
        }
    }

    private fun checkConfig(): DoctorCheck {
        return try {
            val cfg = configRepository.get()
            DoctorCheck("config", "Config integrity", CheckStatus.OK,
                "Config loaded — agent='${cfg.agentIdentity.name}', " +
                "toolRegistry enableAll=${cfg.toolRegistry.enableAllTools}.")
        } catch (e: Exception) {
            Timber.w(e, "Doctor: config read failed")
            DoctorCheck("config", "Config integrity", CheckStatus.FAIL,
                "Failed to read config: ${e.message}")
        }
    }

    private fun checkPython(): DoctorCheck {
        return try {
            val started = Python.isStarted()
            if (!started) {
                DoctorCheck("python", "Python sandbox", CheckStatus.FAIL,
                    "Python runtime not started.")
            } else {
                val ver = Python.getInstance().getModule("sys").get("version").toString()
                DoctorCheck("python", "Python sandbox", CheckStatus.OK,
                    "Ready — ${ver.lineSequence().firstOrNull()?.take(60) ?: "ok"}")
            }
        } catch (e: Exception) {
            DoctorCheck("python", "Python sandbox", CheckStatus.FAIL,
                "Probe failed: ${e.message}")
        }
    }

    private fun checkPlugins(): DoctorCheck {
        return try {
            val plugins = pluginManager.listAllTools()
            DoctorCheck("plugins", "Plugins", CheckStatus.OK,
                "${plugins.size} plugin tool(s) available.", fixable = true)
        } catch (e: Exception) {
            DoctorCheck("plugins", "Plugins", CheckStatus.WARN,
                "Plugin manager reported: ${e.message}", fixable = true)
        }
    }

    private fun checkHeartbeat(): DoctorCheck {
        // Heartbeat monitor runs on a background coroutine scope from
        // ForgeApplication.onCreate(); if the instance is injected, the
        // subsystem is at least wired up. Async health details are surfaced
        // via the main Heartbeat screen.
        return try {
            val klass = heartbeatMonitor.javaClass.simpleName
            DoctorCheck("heartbeat", "Heartbeat", CheckStatus.OK,
                "Monitor wired ($klass). Detailed health lives in the Heartbeat screen.")
        } catch (e: Exception) {
            DoctorCheck("heartbeat", "Heartbeat", CheckStatus.WARN,
                "Heartbeat probe failed: ${e.message}")
        }
    }

    private fun initWorkspace() {
        val base = workspaceDir()
        listOf(
            "memory/daily", "memory/longterm", "memory/skills", "memory/embeddings",
            "cron/queue", "cron/history",
            "plugins", "plugins/.bak",
            "system",
            "companion/episodes",
            "snapshots",
        ).forEach { base.resolve(it).mkdirs() }
    }
}
