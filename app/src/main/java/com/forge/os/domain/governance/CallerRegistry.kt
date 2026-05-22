package com.forge.os.domain.governance

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AuthorizedCaller(
    val packageName: String,
    val permissions: Set<String>,
    val grantedAt: Long = System.currentTimeMillis()
)

@Singleton
class CallerRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storageFile = File(context.filesDir, "workspace/system/callers.json").apply {
        parentFile?.mkdirs()
    }

    private val _callers = MutableStateFlow<List<AuthorizedCaller>>(load())
    val callers: StateFlow<List<AuthorizedCaller>> = _callers.asStateFlow()

    private fun load(): List<AuthorizedCaller> {
        if (!storageFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<AuthorizedCaller>>(storageFile.readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load CallerRegistry")
            emptyList()
        }
    }

    private fun save() {
        try {
            storageFile.writeText(json.encodeToString(_callers.value))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save CallerRegistry")
        }
    }

    fun grant(packageName: String, permission: String) {
        val current = _callers.value.toMutableList()
        val existing = current.find { it.packageName == packageName }
        if (existing != null) {
            current.remove(existing)
            current.add(existing.copy(permissions = existing.permissions + permission))
        } else {
            current.add(AuthorizedCaller(packageName, setOf(permission)))
        }
        _callers.value = current
        save()
    }

    fun revoke(packageName: String) {
        val current = _callers.value.toMutableList()
        current.removeAll { it.packageName == packageName }
        _callers.value = current
        save()
    }

    fun isAuthorized(packageName: String, permission: String): Boolean {
        return _callers.value.find { it.packageName == packageName }?.permissions?.contains(permission) == true
    }
}
