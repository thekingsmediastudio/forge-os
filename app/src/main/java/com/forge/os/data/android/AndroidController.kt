package com.forge.os.data.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forge-side read-only controller for Android system surfaces that don't
 * require runtime permissions. Designed as the foundation for the future
 * "Forge AutoPhone" companion app — each public method maps cleanly to a
 * single headless tool invocation.
 */
@Singleton
class AndroidController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ─── Device info ────────────────────────────────────────────────────
    fun deviceInfo(): JsonElement = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("brand", Build.BRAND)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("fingerprint", Build.FINGERPRINT)
        put("android_version", Build.VERSION.RELEASE)
        put("api_level", Build.VERSION.SDK_INT)
        put("package", context.packageName)
    }

    // ─── Battery ────────────────────────────────────────────────────────
    fun battery(): JsonElement {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return buildJsonObject {
            put("level_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", bm.isCharging)
            put("charge_counter_uah", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            put("current_now_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            put("energy_nwh", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
        }
    }

    // ─── Volume ─────────────────────────────────────────────────────────
    fun volume(): JsonElement {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun pct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            return (am.getStreamVolume(stream) * 100) / max
        }
        return buildJsonObject {
            put("music", pct(AudioManager.STREAM_MUSIC))
            put("ring", pct(AudioManager.STREAM_RING))
            put("notification", pct(AudioManager.STREAM_NOTIFICATION))
            put("alarm", pct(AudioManager.STREAM_ALARM))
            put("voice_call", pct(AudioManager.STREAM_VOICE_CALL))
            put("mode", when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
        }
    }

    fun setVolume(stream: String, percent: Int): JsonElement {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream.lowercase()) {
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val newVal = (max * percent.coerceIn(0, 100)) / 100
        return try {
            am.setStreamVolume(s, newVal, 0)
            buildJsonObject { put("ok", true); put("stream", stream); put("percent", percent) }
        } catch (e: SecurityException) {
            buildJsonObject { put("ok", false); put("error", "Not allowed: ${e.message}") }
        }
    }

    // ─── Brightness (read-only; writing requires WRITE_SETTINGS) ────────
    fun brightness(): JsonElement = buildJsonObject {
        runCatching {
            val cur = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, -1,
            )
            put("value", cur)
            put("range", "0-255")
            val mode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, -1,
            )
            put("auto", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        }.onFailure { put("error", it.message ?: "read failed") }
    }

    // ─── Network state ──────────────────────────────────────────────────
    fun networkState(): JsonElement {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        return buildJsonObject {
            put("connected", active != null)
            if (caps != null) {
                put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                put("unmetered", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
    }

    // ─── Storage ────────────────────────────────────────────────────────
    fun storage(): JsonElement {
        val internal = StatFs(Environment.getDataDirectory().absolutePath)
        val ext = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }
            .getOrNull()
        fun bytes(s: StatFs) = buildJsonObject {
            put("free_bytes", s.availableBytes)
            put("total_bytes", s.totalBytes)
        }
        return buildJsonObject {
            put("internal", bytes(internal))
            if (ext != null) put("external", bytes(ext))
        }
    }

    // ─── Apps ───────────────────────────────────────────────────────────
    fun listApps(userOnly: Boolean = true, limit: Int = 200): JsonElement {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { if (userOnly) (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 else true }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .take(limit)
            .toList()
        return buildJsonArray {
            apps.forEach { ai ->
                add(buildJsonObject {
                    put("package", ai.packageName)
                    put("label", ai.loadLabel(pm).toString())
                    put("launchable", pm.getLaunchIntentForPackage(ai.packageName) != null)
                })
            }
        }
    }

    fun launchApp(packageName: String): JsonElement {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                buildJsonObject { put("ok", true); put("package", packageName) }
            } catch (e: Exception) {
                Timber.w(e, "launchApp failed")
                buildJsonObject { put("ok", false); put("error", e.message ?: "launch failed") }
            }
        } else {
            buildJsonObject { put("ok", false); put("error", "no launch intent for $packageName") }
        }
    }

    // ─── Screen ─────────────────────────────────────────────────────────
    fun screenInfo(): JsonElement {
        val metrics = context.resources.displayMetrics
        return buildJsonObject {
            put("width_px", metrics.widthPixels)
            put("height_px", metrics.heightPixels)
            put("density", metrics.density)
            put("dpi", metrics.densityDpi)
        }
    }

    // ─── Composite snapshot for AutoPhone companion ─────────────────────
    fun snapshot(): JsonElement = buildJsonObject {
        put("device", deviceInfo())
        put("battery", battery())
        put("network", networkState())
        put("storage", storage())
        put("volume", volume())
        put("screen", screenInfo())
    }
}
