package com.forge.os.domain.agent.providers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery tools — expose device power state to Forge OS so the agent can
 * make power-aware decisions (e.g. skip heavy tasks when battery is critical,
 * warn the user when temperature is high, etc.)
 *
 * ## Tools
 *
 * | Tool              | What it does                                                  |
 * |-------------------|---------------------------------------------------------------|
 * | battery_status    | Full battery snapshot: level, charging state, health, temp   |
 * | battery_is_low    | Quick boolean check — is battery below a given threshold?     |
 *
 * ## Permissions
 * None required — battery state is read via a sticky broadcast Intent that
 * any app can receive without declaring permissions.
 */
@Singleton
class BatteryToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "battery_status",
            description = "Get the current battery status: level percentage, whether the device " +
                          "is charging, what it is plugged into (AC/USB/wireless), battery health " +
                          "(good/overheat/dead/etc.), temperature in °C, voltage in mV, and whether " +
                          "battery saver (power save mode) is currently active. " +
                          "No permission required.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "battery_is_low",
            description = "Quick check: is the battery level at or below a threshold? " +
                          "Returns true/false along with the current level. " +
                          "Useful for the agent to avoid starting heavy tasks on a nearly-dead device. " +
                          "Default threshold is 20%.",
            params      = mapOf(
                "threshold" to ("integer" to "Battery level percentage to test against (1-100, default 20)"),
            ),
            required    = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "battery_status" -> batteryStatus()
        "battery_is_low" -> batteryIsLow(args["threshold"]?.toString()?.toIntOrNull() ?: 20)
        else             -> null
    }

    // ── battery_status ────────────────────────────────────────────────────────

    private fun batteryStatus(): String = runCatching {
        val intent = batteryIntent() ?: return err("Battery broadcast not available")

        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct     = if (scale > 0) (level * 100 / scale) else -1

        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING    -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL        -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING-> "not_charging"
            else                                      -> "unknown"
        }

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val pluggedLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC       -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB      -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0                                       -> "unplugged"
            else                                    -> "unknown"
        }

        val health  = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val healthLabel = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD              -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT          -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD              -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE      -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD              -> "cold"
            else                                            -> "unknown"
        }

        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempC   = if (tempRaw >= 0) tempRaw / 10.0 else null

        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

        val pm       = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val powerSave = pm.isPowerSaveMode

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"level\": $pct,")
            appendLine("  \"status\": ${jsonStr(statusLabel)},")
            appendLine("  \"charging\": $charging,")
            appendLine("  \"plugged\": ${jsonStr(pluggedLabel)},")
            appendLine("  \"health\": ${jsonStr(healthLabel)},")
            if (tempC != null) appendLine("  \"temperature_c\": $tempC,")
            if (voltage > 0)   appendLine("  \"voltage_mv\": $voltage,")
            appendLine("  \"power_save_mode\": $powerSave")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read battery status") }

    // ── battery_is_low ────────────────────────────────────────────────────────

    private fun batteryIsLow(threshold: Int): String = runCatching {
        val cap = threshold.coerceIn(1, 100)
        val intent = batteryIntent() ?: return err("Battery broadcast not available")
        val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct    = if (scale > 0) (level * 100 / scale) else -1
        val low    = pct in 0..cap
        """{"ok":true,"level":$pct,"threshold":$cap,"is_low":$low}"""
    }.getOrElse { err(it.message ?: "Failed to read battery level") }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun err(msg: String) = """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name        = name,
            description = description,
            parameters  = FunctionParameters(
                properties = params.mapValues { (_, v) ->
                    ParameterProperty(type = v.first, description = v.second)
                },
                required   = required,
            ),
        ),
    )
}
