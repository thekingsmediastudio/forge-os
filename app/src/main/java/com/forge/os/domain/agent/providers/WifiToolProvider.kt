package com.forge.os.domain.agent.providers

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi tools — surface, scan, and manage device Wi-Fi from Forge OS.
 *
 * ## Tools
 *
 * | Tool               | What it does                                              |
 * |--------------------|-----------------------------------------------------------|
 * | wifi_status        | Current connection details (SSID, IP, signal, frequency)  |
 * | wifi_scan          | Cached list of nearby networks (SSID, BSSID, dBm, band)  |
 * | wifi_open_settings | Open the system Wi-Fi settings screen for manual control  |
 *
 * ## Permission notes
 *
 * * `ACCESS_WIFI_STATE`  — read SSID / scan results (already in manifest)
 * * `ACCESS_FINE_LOCATION` — required since API 26 to get SSID in WifiInfo and
 *   since API 28 to see scan results.  Forge OS must hold this at runtime.
 * * `CHANGE_WIFI_STATE` — used only to request a fresh scan.
 * * Direct enable/disable (setWifiEnabled) is forbidden for non-system apps on
 *   API 29+, so wifi_open_settings handles that user flow instead.
 */
@Singleton
class WifiToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    private val wm: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val cm: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "wifi_status",
            description = "Get the current Wi-Fi connection status: SSID, BSSID, IP address, " +
                          "signal strength (RSSI in dBm), link speed (Mbps), frequency (MHz), " +
                          "and whether Wi-Fi is enabled. Does not require Location permission.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "wifi_scan",
            description = "Return a list of nearby Wi-Fi networks from the last scan cache. " +
                          "Each entry contains: ssid, bssid, rssi (signal dBm), frequency (MHz), " +
                          "band (2.4 GHz or 5 GHz), and capabilities (security type). " +
                          "Requires Location permission to be granted to Forge OS. " +
                          "Results are sorted by signal strength (strongest first). " +
                          "Use wifi_status to see which network is currently connected.",
            params      = mapOf(
                "limit" to ("integer" to "Max number of networks to return (default 20, max 50)"),
            ),
            required    = emptyList(),
        ),
        tool(
            name        = "wifi_open_settings",
            description = "Open the Android Wi-Fi Settings screen so the user can connect to, " +
                          "forget, or configure Wi-Fi networks. Use this when the user wants to " +
                          "switch networks or enable/disable Wi-Fi (direct toggle is restricted " +
                          "by Android on API 29+).",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "wifi_saved_networks",
            description = "List Wi-Fi networks that are configured (saved) on this device. " +
                          "Returns SSID and security type for each saved network.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "wifi_status"         -> wifiStatus()
        "wifi_scan"           -> {
            if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
                return """{"ok":false,"error":"Location permission not granted — required to read Wi-Fi scan results on Android 8+. Grant it in Settings → Apps → Forge OS → Permissions."}"""
            wifiScan(args["limit"]?.toString()?.toIntOrNull() ?: 20)
        }
        "wifi_open_settings"  -> openWifiSettings()
        "wifi_saved_networks" -> savedNetworks()
        else                  -> null
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── wifi_status ───────────────────────────────────────────────────────────

    private fun wifiStatus(): String = runCatching {
        val enabled  = wm.isWifiEnabled
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: get via ConnectivityManager
            val network = cm.activeNetwork ?: return noConnection(enabled)
            val caps    = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return noConnection(enabled)
            @Suppress("DEPRECATION") wm.connectionInfo
        } else {
            @Suppress("DEPRECATION") wm.connectionInfo
        }

        if (info == null || info.networkId == -1) return noConnection(enabled)

        val rssi     = info.rssi
        val quality  = WifiManager.calculateSignalLevel(rssi, 5)   // 0-4
        val qualityLabel = when (quality) {
            4 -> "excellent"
            3 -> "good"
            2 -> "fair"
            1 -> "weak"
            else -> "very weak"
        }

        val ssid  = info.ssid?.trim('"') ?: "<unknown>"
        val bssid = info.bssid ?: ""
        val ip    = intToIp(info.ipAddress)
        val speed = info.linkSpeed   // Mbps
        val freq  = info.frequency   // MHz
        val band  = if (freq >= 5000) "5 GHz" else "2.4 GHz"

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"enabled\": true,")
            appendLine("  \"connected\": true,")
            appendLine("  \"ssid\": ${jsonStr(ssid)},")
            appendLine("  \"bssid\": ${jsonStr(bssid)},")
            appendLine("  \"ip\": ${jsonStr(ip)},")
            appendLine("  \"rssi\": $rssi,")
            appendLine("  \"signal\": ${jsonStr(qualityLabel)},")
            appendLine("  \"link_speed_mbps\": $speed,")
            appendLine("  \"frequency_mhz\": $freq,")
            appendLine("  \"band\": ${jsonStr(band)}")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read Wi-Fi status") }

    private fun noConnection(enabled: Boolean): String {
        return """{"ok":true,"enabled":$enabled,"connected":false,"ssid":null}"""
    }

    // ── wifi_scan ─────────────────────────────────────────────────────────────

    private fun wifiScan(limit: Int): String = runCatching {
        val cap = limit.coerceIn(1, 50)

        @Suppress("DEPRECATION")
        val results = wm.scanResults
        if (results.isNullOrEmpty()) {
            return """{"ok":true,"count":0,"networks":[],"note":"No scan results — Location permission may be required or scan cache is empty. Try wifi_open_settings."}"""
        }

        val sorted = results.sortedByDescending { it.level }.take(cap)
        val arr    = sorted.joinToString(",\n  ") { r ->
            val ssid  = r.SSID?.trim('"') ?: ""
            val bssid = r.BSSID ?: ""
            val band  = if (r.frequency >= 5000) "5 GHz" else "2.4 GHz"
            val caps  = extractSecurity(r.capabilities)
            """{"ssid":${jsonStr(ssid)},"bssid":${jsonStr(bssid)},"rssi":${r.level},"frequency_mhz":${r.frequency},"band":${jsonStr(band)},"security":${jsonStr(caps)}}"""
        }

        """{"ok":true,"count":${sorted.size},"networks":[$arr]}"""
    }.getOrElse { err(it.message ?: "Scan failed — Location permission may not be granted") }

    // ── wifi_open_settings ────────────────────────────────────────────────────

    private fun openWifiSettings(): String = runCatching {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        """{"ok":true,"output":"Opened Wi-Fi Settings"}"""
    }.getOrElse { err("Could not open Wi-Fi Settings: ${it.message}") }

    // ── wifi_saved_networks ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun savedNetworks(): String = runCatching {
        val configs = wm.configuredNetworks
        if (configs.isNullOrEmpty()) {
            return """{"ok":true,"count":0,"networks":[],"note":"No saved networks or permission denied (CHANGE_WIFI_STATE required)"}"""
        }
        val arr = configs.joinToString(",\n  ") { c ->
            val ssid = c.SSID?.trim('"') ?: ""
            """{"ssid":${jsonStr(ssid)},"network_id":${c.networkId}}"""
        }
        """{"ok":true,"count":${configs.size},"networks":[$arr]}"""
    }.getOrElse { err("Could not read saved networks: ${it.message}") }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractSecurity(capabilities: String?): String {
        if (capabilities.isNullOrBlank()) return "Unknown"
        return when {
            "[WPA3" in capabilities -> "WPA3"
            "[WPA2" in capabilities -> "WPA2"
            "[WPA"  in capabilities -> "WPA"
            "[WEP"  in capabilities -> "WEP"
            "[ESS]" == capabilities || capabilities.isBlank() -> "Open"
            else -> "Open"
        }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun ok(msg: String)  = """{"ok":true,"output":"$msg"}"""
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
