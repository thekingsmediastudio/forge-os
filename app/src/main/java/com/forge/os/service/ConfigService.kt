package com.forge.os.service

import android.content.Context
import android.content.SharedPreferences
import com.forge.os.data.api.ConfigResponse
import com.forge.os.data.api.ConfigUpdateRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Manages device configuration for cross-device configuration synchronization.
 * Handles:
 * - Storing and retrieving device configuration
 * - Synchronizing settings between desktop and device
 * - Thread-safe configuration access
 * - Custom configuration values via JSON
 */
@Singleton
class ConfigService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "forge_desktop_config",
        Context.MODE_PRIVATE
    )
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
        private const val KEY_NOTIFICATION_FILTERS = "notification_filters"
        private const val KEY_CUSTOM_PREFIX = "custom_"
        
        // Defaults
        private const val DEFAULT_THEME = "dark"
        private const val DEFAULT_SYNC_ENABLED = true
        private const val DEFAULT_CLIPBOARD_ENABLED = true
    }
    
    /**
     * Retrieves the current device configuration.
     * 
     * @return Current configuration with all settings
     */
    suspend fun getConfig(): ConfigResponse {
        return mutex.withLock {
            try {
                val theme = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
                val syncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, DEFAULT_SYNC_ENABLED)
                val clipboardEnabled = prefs.getBoolean(KEY_CLIPBOARD_ENABLED, DEFAULT_CLIPBOARD_ENABLED)
                
                // Parse notification filters
                val filtersJson = prefs.getString(KEY_NOTIFICATION_FILTERS, "[]") ?: "[]"
                val filters = try {
                    json.decodeFromString<List<String>>(filtersJson)
                } catch (e: Exception) {
                    Timber.w(e, "ConfigService: Failed to parse notification filters, using empty list")
                    emptyList()
                }
                
                // Parse custom configuration values
                val customMap = mutableMapOf<String, JsonElement>()
                prefs.all.forEach { (key, value) ->
                    if (key.startsWith(KEY_CUSTOM_PREFIX)) {
                        val customKey = key.removePrefix(KEY_CUSTOM_PREFIX)
                        try {
                            // Try to parse as JSON, fallback to string
                            val jsonValue = when (value) {
                                is String -> {
                                    try {
                                        json.parseToJsonElement(value)
                                    } catch (e: Exception) {
                                        // If not valid JSON, treat as plain string
                                        json.parseToJsonElement("\"$value\"")
                                    }
                                }
                                is Boolean -> json.parseToJsonElement(value.toString())
                                is Int -> json.parseToJsonElement(value.toString())
                                is Long -> json.parseToJsonElement(value.toString())
                                is Float -> json.parseToJsonElement(value.toString())
                                else -> json.parseToJsonElement("\"$value\"")
                            }
                            customMap[customKey] = jsonValue
                        } catch (e: Exception) {
                            Timber.w(e, "ConfigService: Failed to parse custom value for $customKey")
                        }
                    }
                }
                
                Timber.d("ConfigService: Retrieved config - theme=$theme, sync=$syncEnabled, clipboard=$clipboardEnabled")
                
                ConfigResponse(
                    theme = theme,
                    syncEnabled = syncEnabled,
                    clipboardEnabled = clipboardEnabled,
                    notificationFilters = filters,
                    custom = customMap
                )
            } catch (e: Exception) {
                Timber.e(e, "ConfigService: Failed to get config, returning defaults")
                ConfigResponse(
                    theme = DEFAULT_THEME,
                    syncEnabled = DEFAULT_SYNC_ENABLED,
                    clipboardEnabled = DEFAULT_CLIPBOARD_ENABLED,
                    notificationFilters = emptyList(),
                    custom = emptyMap()
                )
            }
        }
    }
    
    /**
     * Updates the device configuration.
     * Only updates fields that are provided (non-null) in the request.
     * 
     * @param request Configuration update request with fields to update
     * @return true if configuration was successfully updated
     */
    suspend fun updateConfig(request: ConfigUpdateRequest): Boolean {
        return mutex.withLock {
            try {
                val editor = prefs.edit()
                var changed = false
                
                // Update theme if provided
                request.theme?.let { theme ->
                    editor.putString(KEY_THEME, theme)
                    changed = true
                    Timber.d("ConfigService: Updated theme to $theme")
                }
                
                // Update sync enabled if provided
                request.syncEnabled?.let { syncEnabled ->
                    editor.putBoolean(KEY_SYNC_ENABLED, syncEnabled)
                    changed = true
                    Timber.d("ConfigService: Updated sync_enabled to $syncEnabled")
                }
                
                // Update clipboard enabled if provided
                request.clipboardEnabled?.let { clipboardEnabled ->
                    editor.putBoolean(KEY_CLIPBOARD_ENABLED, clipboardEnabled)
                    changed = true
                    Timber.d("ConfigService: Updated clipboard_enabled to $clipboardEnabled")
                }
                
                // Update notification filters if provided
                request.notificationFilters?.let { filters ->
                    val filtersJson = json.encodeToString(filters)
                    editor.putString(KEY_NOTIFICATION_FILTERS, filtersJson)
                    changed = true
                    Timber.d("ConfigService: Updated notification_filters (${filters.size} filters)")
                }
                
                // Update custom configuration values if provided
                request.custom?.let { customMap ->
                    customMap.forEach { (key, value) ->
                        val prefKey = KEY_CUSTOM_PREFIX + key
                        
                        // Store as string representation
                        val stringValue = when (value) {
                            is JsonObject -> value.toString()
                            else -> value.jsonPrimitive.content
                        }
                        
                        editor.putString(prefKey, stringValue)
                        changed = true
                        Timber.d("ConfigService: Updated custom.$key")
                    }
                }
                
                if (changed) {
                    val success = editor.commit()
                    if (success) {
                        Timber.i("ConfigService: Configuration updated successfully")
                    } else {
                        Timber.e("ConfigService: Failed to commit configuration changes")
                    }
                    success
                } else {
                    Timber.d("ConfigService: No configuration changes to apply")
                    true
                }
            } catch (e: Exception) {
                Timber.e(e, "ConfigService: Failed to update configuration")
                false
            }
        }
    }
    
    /**
     * Resets configuration to defaults.
     */
    suspend fun resetToDefaults(): Boolean {
        return mutex.withLock {
            try {
                prefs.edit().clear().commit()
                Timber.i("ConfigService: Configuration reset to defaults")
                true
            } catch (e: Exception) {
                Timber.e(e, "ConfigService: Failed to reset configuration")
                false
            }
        }
    }
    
    /**
     * Checks if a specific feature is enabled.
     */
    suspend fun isFeatureEnabled(feature: String): Boolean {
        return mutex.withLock {
            when (feature) {
                "sync" -> prefs.getBoolean(KEY_SYNC_ENABLED, DEFAULT_SYNC_ENABLED)
                "clipboard" -> prefs.getBoolean(KEY_CLIPBOARD_ENABLED, DEFAULT_CLIPBOARD_ENABLED)
                else -> {
                    Timber.w("ConfigService: Unknown feature: $feature")
                    false
                }
            }
        }
    }
}
