package com.forge.os.domain.sentinel

import com.forge.os.domain.cron.TaskType
import kotlinx.serialization.Serializable

/**
 * The Sentinel Trigger: A reactive automation unit.
 * "Watch for [eventType] where [condition] is met, then execute [payload]."
 */
@Serializable
data class SentinelTrigger(
    val id: String,
    val name: String,
    val eventType: SentinelEventType,
    val condition: String? = null,    // Optional filter (e.g., SSID name, Battery threshold)
    val taskType: TaskType,           // PYTHON, SHELL, or PROMPT
    val payload: String,              // The code or agent prompt to run
    val enabled: Boolean = true,
    val lastFiredAt: Long? = null,
    val fireCount: Int = 0
)

@Serializable
enum class SentinelEventType {
    WIFI_CONNECTED,             // Condition: SSID
    WIFI_DISCONNECTED,          // Condition: SSID
    BATTERY_CHANGED,            // Condition: level percentage (e.g. "20")
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    BLUETOOTH_CONNECTED,        // Condition: Device Name
    BLUETOOTH_DISCONNECTED,     // Condition: Device Name
    SCREEN_ON,
    SCREEN_OFF,
    GEOFENCE_ENTER,             // Condition: Region Name
    GEOFENCE_EXIT,              // Condition: Region Name
    SNATCH_DETECTED             // Labs: Activated by EnvironmentCalibrationEngine
}
