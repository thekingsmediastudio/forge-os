package com.forge.os

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.forge.os.data.workers.CompanionCheckInWorker
import com.forge.os.data.workers.CronExecutionWorker
import com.forge.os.data.workers.DependencyMonitorWorker
import com.forge.os.data.workers.HeartbeatWorker
import com.forge.os.data.workers.MemoryCompressionWorker
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.plugins.BuiltInPlugins
import com.forge.os.domain.agent.UserInputBroker
import com.forge.os.domain.notifications.NotificationAction
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import java.util.UUID

@HiltAndroidApp
class ForgeApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var heartbeatMonitor: HeartbeatMonitor
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var memoryManager: MemoryManager
    @Inject lateinit var builtInPlugins: BuiltInPlugins
    @Inject lateinit var relationshipState: com.forge.os.domain.companion.RelationshipState
    @Inject lateinit var alarmScheduler: com.forge.os.domain.alarms.ForgeAlarmScheduler
    @Inject lateinit var channelManager: com.forge.os.domain.channels.ChannelManager
    // Phase Q
    @Inject lateinit var controlPlane: com.forge.os.domain.control.AgentControlPlane
    @Inject lateinit var notificationActions: com.forge.os.domain.notifications.NotificationActionRegistry
    @Inject lateinit var proactiveScheduler: com.forge.os.domain.proactive.ProactiveScheduler
    @Inject lateinit var pluginExporter: com.forge.os.domain.plugins.PluginExporter
    // Lazy because ToolRegistry has heavy graph dependencies and we don't want
    // to force its construction during Application.onCreate().
    @Inject lateinit var toolRegistryProvider: Lazy<com.forge.os.domain.agent.ToolRegistry>
    @Inject lateinit var userInputBroker: UserInputBroker
    // AutoPhone companion binding — starts connecting in background immediately
    @Inject lateinit var autoPhoneConnection: com.forge.os.data.android.AutoPhoneConnection
    // Universal bridge manager — discovers all IForgeBridgeService apps
    @Inject lateinit var forgeBridgeManager: com.forge.os.data.bridge.ForgeBridgeManager
    // forge-bridge-android AI provider discovery — probes localhost:8745 on startup
    @Inject lateinit var forgeBridgeDiscovery: com.forge.os.data.api.ForgeBridgeDiscovery

    private val notificationActionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // WorkManager on-demand initialization. The default startup-based init is
    // disabled in AndroidManifest.xml (tools:node="remove" on the
    // WorkManagerInitializer meta-data). Without this provider WorkManager
    // would crash trying to instantiate @HiltWorker classes via reflection
    // (NoSuchMethodException for a Context+WorkerParameters constructor).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        // MUST run before super.onCreate(), because Hilt's injection cycle
        // (triggered inside super.onCreate) transitively constructs
        // PythonRunner, which calls Python.getInstance(). If Python isn't
        // started yet, Chaquopy throws "Cannot use GenericPlatform on Android".
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        super.onCreate()
        // initWebView() must be called AFTER super.onCreate() so the
        // Application context is fully initialized before any WebView API
        // is touched. Calling it before super.onCreate() causes
        // IllegalStateException / RuntimeException on Android 9+ because
        // the WebView subsystem hasn't been set up yet.
        initWebView()

        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        initWorkspaceDirs()
        heartbeatMonitor.start()
        HeartbeatWorker.schedule(workManager, intervalMinutes = 15)
        MemoryCompressionWorker.schedule(workManager)
        CronExecutionWorker.schedule(workManager)

        val fm = configRepository.get().friendMode

        // Phase L — schedule the proactive check-in worker only when Friend
        // Mode AND proactive check-ins are both opted in. Cancel otherwise so
        // a turned-off setting actually stops firing.
        if (fm.enabled && fm.proactiveCheckInsEnabled) {
            CompanionCheckInWorker.schedule(workManager, intervalMinutes = 15)
        } else {
            CompanionCheckInWorker.cancel(workManager)
        }

        // Phase O-2 — schedule the nightly dependency monitor. Runs regardless
        // of Friend Mode so it's ready the moment the user opts in. The job
        // itself exits early when dependencyMonitorEnabled is false.
        DependencyMonitorWorker.schedule(workManager)

        memoryManager.rebuildIndex()
        builtInPlugins.seedIfMissing()
        // Phase N — derive relationship counters from existing episodes on startup.
        runCatching { relationshipState.recomputeFromEpisodes() }
        runCatching { alarmScheduler.rescheduleAll() }
        runCatching { channelManager.startAll() }
        // AutoPhone — bind in background; tools degrade gracefully if not installed
        runCatching { autoPhoneConnection.connect() }
        // Bridge manager — discover and bind to all IForgeBridgeService apps
        runCatching { forgeBridgeManager.refresh() }
        // forge-bridge-android AI provider — probe localhost:8745 in background.
        // If running, FORGE_BRIDGE becomes the primary provider automatically.
        if (configRepository.get().forgeBridge.autoDiscover) {
            notificationActionScope.launch {
                runCatching { forgeBridgeDiscovery.probe() }
                    .onSuccess { hs ->
                        if (hs.available) Timber.i(
                            "ForgeBridge online — v${hs.version} — " +
                            "${hs.connectedProviders.size} provider(s) connected"
                        )
                    }
                    .onFailure { Timber.d("ForgeBridge probe skipped: ${it.message}") }
            }
        }

        // Phase Q — touch the control plane so its capabilities are loaded
        // before any subsystem queries them, register a default no-op
        // dispatcher for notification actions (real dispatcher is wired by
        // MainActivity once the agent runtime is alive), and enqueue the
        // proactive worker (gated by capability — safe to schedule always).
        runCatching {
            Timber.i(controlPlane.summary())
            notificationActions.setDispatcher { action ->
                handleNotificationAction(action)
            }
            proactiveScheduler.ensureScheduled()
        }
        val identity = configRepository.get().agentIdentity
        Timber.i("Forge OS Kernel initialized — Agent: ${identity.name} — Memory + Cron + Plugins online")
    }

    /**
     * Real dispatcher for notification action button taps. Routes by
     * [NotificationAction.kind]:
     *   - "open_screen"  → launches MainActivity with `nav` extra (and
     *                      optional `companionSeed` payload field).
     *   - "chat_message" → if a run is awaiting input on the UI route, hand
     *                      the text to UserInputBroker; otherwise open the
     *                      chat screen with the text seeded as a pending
     *                      companion seed so it lands in the input field.
     *   - "tool_call"    → invokes ToolRegistry.dispatch on a background
     *                      coroutine, so the agent runs the tool even though
     *                      no chat turn is active.
     *   - anything else  → logged.
     */
    private fun handleNotificationAction(action: NotificationAction) {
        try {
            val payload = runCatching { notificationJson.parseToJsonElement(action.payloadJson).jsonObject }
                .getOrNull()
            when (action.kind) {
                "open_screen" -> {
                    val screen = payload?.get("screen")?.jsonPrimitive?.contentOrNull ?: "chat"
                    val seed = payload?.get("seed")?.jsonPrimitive?.contentOrNull
                    val launch = packageManager.getLaunchIntentForPackage(packageName)
                        ?: android.content.Intent()
                            .setClassName(packageName, "com.forge.os.presentation.MainActivity")
                    launch.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    launch.putExtra("nav", screen)
                    if (!seed.isNullOrBlank()) launch.putExtra("companionSeed", seed)
                    startActivity(launch)
                }
                "chat_message" -> {
                    val text = payload?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: action.label
                    if (userInputBroker.isAwaiting(com.forge.os.domain.agent.InputRoute.UI)) {
                        notificationActionScope.launch {
                            runCatching {
                                userInputBroker.submitResponse(
                                    com.forge.os.domain.agent.InputRoute.UI, text)
                            }.onFailure { Timber.w(it, "submitResponse failed") }
                        }
                    } else {
                        // No active turn — open the chat with the text seeded.
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                            ?: android.content.Intent()
                                .setClassName(packageName, "com.forge.os.presentation.MainActivity")
                        launch.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        launch.putExtra("nav", "chat")
                        launch.putExtra("companionSeed", text)
                        startActivity(launch)
                    }
                }
                "tool_call" -> {
                    val name = payload?.get("name")?.jsonPrimitive?.contentOrNull
                    val args = payload?.get("args")?.toString() ?: "{}"
                    if (name.isNullOrBlank()) {
                        Timber.w("NotificationAction tool_call without name: ${action.label}")
                        return
                    }
                    notificationActionScope.launch {
                        runCatching {
                            val result = toolRegistryProvider.get().dispatch(
                                toolName = name,
                                argsJson = args,
                                toolCallId = "notif_" + UUID.randomUUID().toString())
                            Timber.i("NotificationAction tool_call $name → " +
                                if (result.isError) "ERR ${result.output.take(120)}"
                                else "OK ${result.output.take(120)}")
                        }.onFailure { Timber.e(it, "tool_call $name failed") }
                    }
                }
                else -> Timber.w("NotificationAction: unknown kind '${action.kind}'")
            }
        } catch (t: Throwable) {
            Timber.e(t, "handleNotificationAction failed for ${action.label}")
        }
    }

    private fun initWorkspaceDirs() {
        val base = filesDir.resolve("workspace")
        listOf(
            "memory/daily", "memory/longterm", "memory/skills", "memory/embeddings",
            "cron/queue", "cron/history",
            "plugins", "plugins/.bak",
            "system",
            "companion/episodes",
            "snapshots",
        ).forEach { base.resolve(it).mkdirs() }
    }

    private fun initWebView() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // setDataDirectorySuffix must be called after Application.onCreate()
            // completes. It may only be set once per process; subsequent calls
            // (e.g. from a test runner or a second call on hot-restart) throw
            // IllegalStateException which we safely ignore.
            runCatching {
                android.webkit.WebView.setDataDirectorySuffix("forge_v1")
            }.onFailure { t ->
                when (t) {
                    // Already set — completely harmless.
                    is IllegalStateException -> Unit
                    // Some OEMs throw a plain RuntimeException instead.
                    is RuntimeException -> android.util.Log.w(
                        "ForgeKernel", "WebView suffix skipped (OEM variant): ${t.message}")
                    else -> android.util.Log.e(
                        "ForgeKernel", "WebView suffix failed unexpectedly", t)
                }
            }
        }
    }
}
