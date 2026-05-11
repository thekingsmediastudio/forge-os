package com.forge.os.domain.agent.providers

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device info tools — surface hardware identity and runtime context so the
 * agent can adapt its behaviour to the exact device it is running on.
 *
 * ## Tools
 *
 * | Tool                      | What it does                                              |
 * |---------------------------|-----------------------------------------------------------|
 * | device_info               | Model, manufacturer, Android version, SDK, ABI, hardware  |
 * | device_screen_info        | Resolution, density, DPI, orientation, refresh rate       |
 * | device_locale_info        | Current locale, language, region, timezone, UTC offset    |
 * | device_memory_info        | Total / available RAM and internal storage                |
 * | device_installed_apps     | List of installed user-facing apps (label + package)      |
 *
 * ## Permissions
 * None required — all data is from public Build fields, DisplayMetrics,
 * and PackageManager. QUERY_ALL_PACKAGES (already declared) is needed for
 * device_installed_apps to see all apps on API 30+.
 */
@Singleton
class DeviceInfoToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "device_info",
            description = "Get hardware and OS identity: manufacturer, model, brand, device " +
                          "codename, Android version (e.g. 14), SDK level (e.g. 34), security " +
                          "patch date, supported ABIs (arm64-v8a etc.), hardware name, and " +
                          "whether the build is a debug or release build. No permission required.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "device_screen_info",
            description = "Get the physical display properties: pixel width and height, " +
                          "logical density (dp scale factor), dots-per-inch (xdpi/ydpi), " +
                          "current orientation (portrait/landscape), and refresh rate in Hz. " +
                          "Useful for calibrating tap/swipe coordinates sent to AutoPhone.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "device_locale_info",
            description = "Get the device's current locale, language code (e.g. en), country " +
                          "code (e.g. US), display language name, timezone ID (e.g. America/New_York), " +
                          "UTC offset in hours, and whether the device uses 24-hour time format. " +
                          "Useful for formatting dates, scheduling tasks, and language-aware responses.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "device_memory_info",
            description = "Get RAM and storage statistics: total RAM, available RAM, low-memory " +
                          "threshold, whether the system is in a low-memory state, total internal " +
                          "storage, and free internal storage — all in megabytes. " +
                          "Useful before starting memory-intensive automation tasks.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "device_installed_apps",
            description = "List apps installed on the device. Returns app label and package name " +
                          "for each. By default returns only launchable (user-facing) apps. " +
                          "Set include_system=true to also include system/background packages. " +
                          "Requires QUERY_ALL_PACKAGES (declared in Forge OS manifest).",
            params      = mapOf(
                "include_system" to ("boolean" to "Include system and background packages (default false)"),
                "filter"         to ("string"  to "Optional text to filter results by app label or package name (case-insensitive)"),
                "limit"          to ("integer" to "Max apps to return (default 50, max 200)"),
            ),
            required    = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "device_info"          -> deviceInfo()
        "device_screen_info"   -> screenInfo()
        "device_locale_info"   -> localeInfo()
        "device_memory_info"   -> memoryInfo()
        "device_installed_apps"-> installedApps(
            includeSystem = args["include_system"]?.toString()?.toBooleanStrictOrNull() ?: false,
            filter        = args["filter"]?.toString() ?: "",
            limit         = args["limit"]?.toString()?.toIntOrNull() ?: 50,
        )
        else -> null
    }

    // ── device_info ───────────────────────────────────────────────────────────

    private fun deviceInfo(): String = runCatching {
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"manufacturer\": ${jsonStr(Build.MANUFACTURER)},")
            appendLine("  \"brand\": ${jsonStr(Build.BRAND)},")
            appendLine("  \"model\": ${jsonStr(Build.MODEL)},")
            appendLine("  \"device\": ${jsonStr(Build.DEVICE)},")
            appendLine("  \"product\": ${jsonStr(Build.PRODUCT)},")
            appendLine("  \"hardware\": ${jsonStr(Build.HARDWARE)},")
            appendLine("  \"android_version\": ${jsonStr(Build.VERSION.RELEASE)},")
            appendLine("  \"sdk_int\": ${Build.VERSION.SDK_INT},")
            appendLine("  \"security_patch\": ${jsonStr(Build.VERSION.SECURITY_PATCH)},")
            appendLine("  \"supported_abis\": ${jsonStr(abis)},")
            appendLine("  \"build_type\": ${jsonStr(Build.TYPE)},")
            appendLine("  \"fingerprint\": ${jsonStr(Build.FINGERPRINT.take(80))}")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read device info") }

    // ── device_screen_info ────────────────────────────────────────────────────

    private fun screenInfo(): String = runCatching {
        val wm      = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val config    = context.resources.configuration
        val orient    = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val refreshHz = runCatching {
            @Suppress("DEPRECATION") wm.defaultDisplay.refreshRate
        }.getOrElse { 60f }

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"width_px\": ${metrics.widthPixels},")
            appendLine("  \"height_px\": ${metrics.heightPixels},")
            appendLine("  \"density\": ${metrics.density},")
            appendLine("  \"density_dpi\": ${metrics.densityDpi},")
            appendLine("  \"xdpi\": ${metrics.xdpi},")
            appendLine("  \"ydpi\": ${metrics.ydpi},")
            appendLine("  \"width_dp\": ${(metrics.widthPixels / metrics.density).toInt()},")
            appendLine("  \"height_dp\": ${(metrics.heightPixels / metrics.density).toInt()},")
            appendLine("  \"orientation\": ${jsonStr(orient)},")
            appendLine("  \"refresh_rate_hz\": ${"%.1f".format(refreshHz)}")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read screen info") }

    // ── device_locale_info ────────────────────────────────────────────────────

    private fun localeInfo(): String = runCatching {
        val locale    = Locale.getDefault()
        val tz        = TimeZone.getDefault()
        val offsetMs  = tz.rawOffset + tz.dstSavings
        val offsetHrs = offsetMs / 3_600_000.0
        val offsetStr = "%+.1f".format(offsetHrs)

        val use24     = android.text.format.DateFormat.is24HourFormat(context)

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"locale\": ${jsonStr(locale.toLanguageTag())},")
            appendLine("  \"language\": ${jsonStr(locale.language)},")
            appendLine("  \"country\": ${jsonStr(locale.country)},")
            appendLine("  \"display_language\": ${jsonStr(locale.displayLanguage)},")
            appendLine("  \"display_country\": ${jsonStr(locale.displayCountry)},")
            appendLine("  \"timezone_id\": ${jsonStr(tz.id)},")
            appendLine("  \"timezone_display\": ${jsonStr(tz.getDisplayName(false, TimeZone.SHORT))},")
            appendLine("  \"utc_offset_hours\": $offsetStr,")
            appendLine("  \"use_24h_clock\": $use24")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read locale info") }

    // ── device_memory_info ────────────────────────────────────────────────────

    private fun memoryInfo(): String = runCatching {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val totalRamMb  = info.totalMem  / (1024 * 1024)
        val availRamMb  = info.availMem  / (1024 * 1024)
        val thresholdMb = info.threshold / (1024 * 1024)

        val stat        = StatFs(context.filesDir.absolutePath)
        val totalStorMb = stat.totalBytes / (1024 * 1024)
        val freeStorMb  = stat.availableBytes / (1024 * 1024)

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"ram_total_mb\": $totalRamMb,")
            appendLine("  \"ram_available_mb\": $availRamMb,")
            appendLine("  \"ram_low_threshold_mb\": $thresholdMb,")
            appendLine("  \"ram_low_memory\": ${info.lowMemory},")
            appendLine("  \"storage_total_mb\": $totalStorMb,")
            appendLine("  \"storage_free_mb\": $freeStorMb")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read memory info") }

    // ── device_installed_apps ─────────────────────────────────────────────────

    private fun installedApps(includeSystem: Boolean, filter: String, limit: Int): String = runCatching {
        val cap = limit.coerceIn(1, 200)
        val pm  = context.packageManager

        val flags = if (includeSystem) 0 else PackageManager.GET_META_DATA
        val packages = pm.getInstalledApplications(0)

        val filtered = packages
            .asSequence()
            .filter { app ->
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (!includeSystem && isSystem) return@filter false
                // Must be launchable when not including system
                if (!includeSystem && pm.getLaunchIntentForPackage(app.packageName) == null) return@filter false
                true
            }
            .map { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrElse { app.packageName }
                Pair(label, app.packageName)
            }
            .filter { (label, pkg) ->
                filter.isBlank() ||
                label.contains(filter, ignoreCase = true) ||
                pkg.contains(filter, ignoreCase = true)
            }
            .sortedBy { (label, _) -> label.lowercase() }
            .take(cap)
            .toList()

        val arr = filtered.joinToString(",\n  ") { (label, pkg) ->
            """{"label":${jsonStr(label)},"package":${jsonStr(pkg)}}"""
        }
        """{"ok":true,"count":${filtered.size},"apps":[$arr]}"""
    }.getOrElse { err(it.message ?: "Failed to list installed apps") }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
