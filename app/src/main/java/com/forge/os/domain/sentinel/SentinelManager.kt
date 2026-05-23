package com.forge.os.domain.sentinel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.cron.TaskType
import com.forge.os.domain.security.ProviderSpec
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Forge Sentinel: Monitors system reality and reacts when conditions match.
 * "Watch for SSID 'Starbucks' and run a Python script."
 */
@Singleton
class SentinelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SentinelRepository,
    private val sandboxManager: SandboxManager,
    private val reActAgent: Lazy<ReActAgent>,
    private val aiApiManager: Lazy<AiApiManager>,
    private val trustManager: com.forge.os.domain.security.TrustScoreManager,
) {
    private lateinit var calibrationEngine: EnvironmentCalibrationEngine // Settable after init

    fun setCalibrationEngine(engine: EnvironmentCalibrationEngine) {
        this.calibrationEngine = engine
        // We could also listen to a flow here, but fire() is simpler for now
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastWifiSsid: String? = null

    init {
        registerSystemListeners()
    }

    private fun registerSystemListeners() {
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(SentinelReceiver(), filter)
        Timber.i("SentinelManager: Global system monitoring active.")
    }

    private inner class SentinelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ConnectivityManager.CONNECTIVITY_ACTION -> checkWifiTriggers()
                Intent.ACTION_BATTERY_CHANGED -> checkBatteryTriggers(intent)
                Intent.ACTION_POWER_CONNECTED -> fire(SentinelEventType.POWER_CONNECTED)
                Intent.ACTION_POWER_DISCONNECTED -> fire(SentinelEventType.POWER_DISCONNECTED)
                Intent.ACTION_SCREEN_ON -> fire(SentinelEventType.SCREEN_ON)
                Intent.ACTION_SCREEN_OFF -> fire(SentinelEventType.SCREEN_OFF)
            }
        }
    }

    private fun checkWifiTriggers() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info.ssid.trim('"') // SSID often comes quoted
            
            if (ssid != lastWifiSsid && ssid != "<unknown ssid>") {
                lastWifiSsid = ssid
                fire(SentinelEventType.WIFI_CONNECTED, ssid)
            }
        } else {
            if (lastWifiSsid != null) {
                fire(SentinelEventType.WIFI_DISCONNECTED, lastWifiSsid!!)
                lastWifiSsid = null
            }
        }
    }

    private fun checkBatteryTriggers(intent: Intent) {
        val level = intent.getIntExtra("level", -1)
        if (level != -1) {
            fire(SentinelEventType.BATTERY_CHANGED, level.toString())
        }
    }

    fun fire(type: SentinelEventType, data: String? = null) {
        scope.launch {
            // High-priority Internal Reflex: Snatch Detection
            if (type == SentinelEventType.SNATCH_DETECTED) {
                val currentTrust = trustManager.trustScore.value
                val vigilance = trustManager.vigilanceLevel.value
                
                Timber.w("Sentinel: Snatch detected! Current Trust: $currentTrust, Vigilance: $vigilance")
                
                // If we are in a low trust/high vigilance state, trigger GHOST MODE
                if (currentTrust < 30 || vigilance == com.forge.os.domain.security.TrustScoreManager.VigilanceLevel.PARANOID) {
                    Timber.e("SENTINEL: TRUST COMPROMISED. TRIGGERING GHOST PROTOCOL.")
                    sandboxManager.triggerGhostMode()
                }
            }

            val triggers = repository.all().filter { it.enabled && it.eventType == type }
            for (trigger in triggers) {
                // If a condition is specified, it must match the event data
                if (trigger.condition != null && data != null) {
                    if (!data.contains(trigger.condition, ignoreCase = true)) continue
                }
                
                Timber.i("Sentinel Trigger Fired: ${trigger.name} (${type.name})")
                executeTask(trigger)
                
                // Update stats
                repository.save(trigger.copy(
                    lastFiredAt = System.currentTimeMillis(),
                    fireCount = trigger.fireCount + 1
                ))
            }
        }
    }

    private suspend fun executeTask(trigger: SentinelTrigger) {
        try {
            when (trigger.taskType) {
                TaskType.PYTHON -> sandboxManager.executePython(trigger.payload)
                TaskType.SHELL -> sandboxManager.executeShell(trigger.payload)
                TaskType.PROMPT -> {
                    // Start an autonomous agent run for this trigger
                    val events = reActAgent.get().run(
                        userMessage = "SENTINEL EVENT: ${trigger.name}. Action prompt: ${trigger.payload}"
                    ).toList()
                    Timber.d("Sentinel Agent Run for [${trigger.name}] completed.")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute sentinel task: ${trigger.name}")
        }
    }
}
