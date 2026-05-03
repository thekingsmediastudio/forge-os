package com.forge.os.domain.agent.providers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresPermission
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** BluetoothProfile.HID_HOST (API 34+); literal value avoids missing symbol on older compile SDK stubs. */
private const val BLUETOOTH_PROFILE_HID_HOST = 4

/**
 * Bluetooth tools — inspect and navigate Bluetooth from Forge OS.
 *
 * ## Tools
 *
 * | Tool                      | What it does                                               |
 * |---------------------------|------------------------------------------------------------|
 * | bluetooth_status          | Is Bluetooth on? Local device name and address             |
 * | bluetooth_paired_devices  | All bonded/paired devices: name, address, type, class      |
 * | bluetooth_connected_devices | Devices actively connected right now (audio/HID/etc.)    |
 * | bluetooth_open_settings   | Open system Bluetooth settings screen                      |
 *
 * ## Permission notes
 *
 * Pre-API-31:  BLUETOOTH + BLUETOOTH_ADMIN (normal, no runtime grant needed)
 * API 31+:     BLUETOOTH_CONNECT (runtime, needed for paired/connected device names)
 *              BLUETOOTH_SCAN    (runtime, needed for scan operations)
 *
 * All four are declared in AndroidManifest.xml. The agent gracefully reports
 * a permission error when BLUETOOTH_CONNECT has not been granted at runtime.
 *
 * Direct enable/disable (BluetoothAdapter.enable/disable) is deprecated from
 * API 33 and restricted for non-system apps from API 31, so bluetooth_open_settings
 * handles that flow instead.
 */
@Singleton
class BluetoothToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    private val btManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "bluetooth_status",
            description = "Get the current Bluetooth status: whether it is enabled, the local " +
                          "device name, and the device MAC address. Does not require runtime permission.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "bluetooth_paired_devices",
            description = "List all Bluetooth devices that are bonded (paired) with this device. " +
                          "Returns name, MAC address, device type (classic/LE/dual), and device class " +
                          "(headset, phone, computer, etc.) for each device. " +
                          "Requires BLUETOOTH_CONNECT permission on Android 12+.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "bluetooth_connected_devices",
            description = "List Bluetooth devices that are actively connected right now across " +
                          "common profiles: A2DP (audio sink), Headset (HSP/HFP), HID (keyboard/mouse), " +
                          "and GATT (BLE). Each entry shows name, address, and which profile is active. " +
                          "Requires BLUETOOTH_CONNECT permission on Android 12+.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "bluetooth_open_settings",
            description = "Open the Android Bluetooth Settings screen. Use this to enable/disable " +
                          "Bluetooth, pair new devices, or manage connections when direct API control " +
                          "is restricted (Android 12+).",
            params      = emptyMap(),
            required    = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "bluetooth_status"           -> bluetoothStatus()
        "bluetooth_paired_devices"   -> pairedDevices()
        "bluetooth_connected_devices"-> connectedDevices()
        "bluetooth_open_settings"    -> openSettings()
        else                         -> null
    }

    // ── bluetooth_status ──────────────────────────────────────────────────────

    private fun bluetoothStatus(): String = runCatching {
        val a = adapter
        if (a == null) return """{"ok":true,"available":false,"note":"Bluetooth hardware not present"}"""
        val enabled = a.isEnabled
        val name    = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // BLUETOOTH_CONNECT required for name; catch SecurityException gracefully
                a.name ?: "Unknown"
            } else {
                @Suppress("DEPRECATION") a.name ?: "Unknown"
            }
        }.getOrElse { "<permission required>" }

        val address = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "<redacted on API31+>"
            else @Suppress("DEPRECATION") a.address ?: "Unknown"
        }.getOrElse { "<permission required>" }

        val stateLabel = when (a.state) {
            BluetoothAdapter.STATE_ON           -> "on"
            BluetoothAdapter.STATE_OFF          -> "off"
            BluetoothAdapter.STATE_TURNING_ON   -> "turning_on"
            BluetoothAdapter.STATE_TURNING_OFF  -> "turning_off"
            else                                -> "unknown"
        }

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"available\": true,")
            appendLine("  \"enabled\": $enabled,")
            appendLine("  \"state\": ${jsonStr(stateLabel)},")
            appendLine("  \"name\": ${jsonStr(name)},")
            appendLine("  \"address\": ${jsonStr(address)}")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read Bluetooth status") }

    // ── bluetooth_paired_devices ──────────────────────────────────────────────

    private fun pairedDevices(): String = runCatching {
        val a = adapter ?: return """{"ok":false,"error":"Bluetooth not available"}"""
        if (!a.isEnabled) return """{"ok":true,"enabled":false,"devices":[]}"""

        val bonded: Set<BluetoothDevice> = runCatching {
            a.bondedDevices ?: emptySet()
        }.getOrElse {
            return err("BLUETOOTH_CONNECT permission not granted — grant it to read paired devices")
        }

        val arr = bonded.joinToString(",\n  ") { d ->
            val name    = deviceName(d)
            val address = d.address ?: "Unknown"
            val type    = deviceTypeName(d.type)
            val cls     = deviceClassName(d.bluetoothClass?.majorDeviceClass ?: 0)
            """{"name":${jsonStr(name)},"address":${jsonStr(address)},"type":${jsonStr(type)},"class":${jsonStr(cls)}}"""
        }
        """{"ok":true,"count":${bonded.size},"devices":[$arr]}"""
    }.getOrElse { err(it.message ?: "Failed to read paired devices") }

    // ── bluetooth_connected_devices ───────────────────────────────────────────

    private fun connectedDevices(): String = runCatching {
        val a = adapter ?: return """{"ok":false,"error":"Bluetooth not available"}"""
        if (!a.isEnabled) return """{"ok":true,"enabled":false,"devices":[]}"""

        val profileIds = listOf(
            BluetoothProfile.A2DP          to "A2DP (Audio)",
            BluetoothProfile.HEADSET       to "Headset (HFP/HSP)",
            BLUETOOTH_PROFILE_HID_HOST     to "HID (Keyboard/Mouse/Gamepad)",
            BluetoothProfile.GATT          to "GATT (BLE)",
        )

        data class ConnectedEntry(val device: BluetoothDevice, val profile: String)
        val found = mutableListOf<ConnectedEntry>()
        val latch  = java.util.concurrent.CountDownLatch(profileIds.size)

        for ((profileId, profileLabel) in profileIds) {
            runCatching {
                a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching {
                            proxy.connectedDevices.forEach { d ->
                                synchronized(found) { found.add(ConnectedEntry(d, profileLabel)) }
                            }
                        }
                        a.closeProfileProxy(profile, proxy)
                        latch.countDown()
                    }
                    override fun onServiceDisconnected(profile: Int) { latch.countDown() }
                }, profileId)
            }.onFailure { latch.countDown() }
        }

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        // De-duplicate by address, aggregating profiles
        val byAddress = found.groupBy { it.device.address }
        val arr = byAddress.values.joinToString(",\n  ") { entries ->
            val d       = entries.first().device
            val name    = deviceName(d)
            val address = d.address ?: "Unknown"
            val profiles = entries.map { it.profile }
            """{"name":${jsonStr(name)},"address":${jsonStr(address)},"profiles":${jsonArray(profiles)}}"""
        }
        """{"ok":true,"count":${byAddress.size},"devices":[$arr]}"""
    }.getOrElse { err(it.message ?: "Failed to read connected devices — BLUETOOTH_CONNECT permission may be required") }

    // ── bluetooth_open_settings ───────────────────────────────────────────────

    private fun openSettings(): String = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        """{"ok":true,"output":"Opened Bluetooth Settings"}"""
    }.getOrElse { err("Could not open Bluetooth Settings: ${it.message}") }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun deviceName(d: BluetoothDevice): String = runCatching {
        d.name ?: d.address ?: "Unknown"
    }.getOrElse { d.address ?: "Unknown" }

    private fun deviceTypeName(type: Int) = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE      -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL    -> "Dual (Classic+BLE)"
        else                                -> "Unknown"
    }

    private fun deviceClassName(major: Int) = when (major) {
        0x0100 -> "Computer"
        0x0200 -> "Phone"
        0x0300 -> "LAN/Network"
        0x0400 -> "Audio/Video (Headset/Speaker)"
        0x0500 -> "Peripheral (Keyboard/Mouse)"
        0x0600 -> "Imaging (Printer/Camera)"
        0x0700 -> "Wearable"
        0x0800 -> "Toy"
        0x0900 -> "Health Device"
        else   -> "Uncategorized"
    }

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun jsonArray(items: List<String>) =
        "[${items.joinToString(",") { jsonStr(it) }}]"

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
