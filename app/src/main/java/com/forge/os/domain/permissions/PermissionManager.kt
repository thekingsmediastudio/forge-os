package com.forge.os.domain.permissions

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a permission requirement with metadata.
 */
data class PermissionItem(
    val permission: String,
    val name: String,
    val description: String,
    val icon: String,
    val isRequired: Boolean = true,
    val isGranted: Boolean = false
)

/**
 * Manages app permissions and first-launch permission onboarding.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    private val _permissionsRequested = MutableStateFlow(false)
    val permissionsRequested: StateFlow<Boolean> = _permissionsRequested.asStateFlow()

    private val _permissionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionStates: StateFlow<Map<String, Boolean>> = _permissionStates.asStateFlow()

    companion object {
        private const val PREFS_NAME = "forge_permission_prefs"
        private const val KEY_PERMISSIONS_REQUESTED = "permissions_requested"

        /** All permissions the app may need */
        val REQUIRED_PERMISSIONS = listOf(
            PermissionItem(
                permission = Manifest.permission.RECORD_AUDIO,
                name = "Microphone",
                description = "For voice input and voice mode conversations",
                icon = "🎤",
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                name = "Notifications",
                description = "For alerts, reminders, and background task updates",
                icon = "🔔",
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.READ_EXTERNAL_STORAGE,
                name = "Storage",
                description = "For accessing files and documents",
                icon = "📁",
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.CAMERA,
                name = "Camera",
                description = "For scanning documents and OCR (optional)",
                icon = "📷",
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                name = "Location",
                description = "For location-based features (optional)",
                icon = "📍",
                isRequired = false
            )
        )
    }

    init {
        _permissionsRequested.value = prefs.getBoolean(KEY_PERMISSIONS_REQUESTED, false)
        refreshPermissionStates()
    }

    /** Check if permission onboarding should be shown */
    fun shouldShowPermissionOnboarding(): Boolean {
        return !_permissionsRequested.value
    }

    /** Mark permission onboarding as completed */
    fun markPermissionsRequested() {
        _permissionsRequested.value = true
        prefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply()
    }

    /** Refresh the current permission states */
    fun refreshPermissionStates() {
        val states = mutableMapOf<String, Boolean>()
        REQUIRED_PERMISSIONS.forEach { item ->
            states[item.permission] = isPermissionGranted(item.permission)
        }
        _permissionStates.value = states
    }

    /** Check if a specific permission is granted */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Get all permissions with their current states */
    fun getPermissionsWithStates(): List<PermissionItem> {
        return REQUIRED_PERMISSIONS.map { item ->
            item.copy(isGranted = isPermissionGranted(item.permission))
        }
    }

    /** Get list of permissions that need to be requested */
    fun getPermissionsToRequest(): Array<String> {
        return REQUIRED_PERMISSIONS
            .filter { !isPermissionGranted(it.permission) }
            .map { it.permission }
            .toTypedArray()
    }

    /** Check if all required permissions are granted */
    fun areRequiredPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS
            .filter { it.isRequired }
            .all { isPermissionGranted(it.permission) }
    }

    /** Reset permission onboarding (for testing) */
    fun resetPermissionOnboarding() {
        _permissionsRequested.value = false
        prefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, false).apply()
    }
}
