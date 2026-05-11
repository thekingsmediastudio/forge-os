package com.forge.os.domain.user

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user preferences and settings that persist across app restarts.
 * 
 * Remembers:
 * - Project locations and preferences
 * - UI preferences (dark mode, theme, etc.)
 * - Interaction history and patterns
 * - Favorite tools and workflows
 * - Custom shortcuts and aliases
 */
@Singleton
class UserPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val preferencesFile: File get() = context.filesDir.resolve("workspace/system/user_preferences.json")

    // All I/O runs on IO dispatcher; mutex prevents concurrent writes
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // In-memory cache so reads never hit disk on the main thread
    @Volatile private var cachedPreferences: UserPreferences? = null

    // Debounce: coalesce rapid writes into a single flush after 500 ms
    private var pendingFlushJob: Job? = null

    init {
        preferencesFile.parentFile?.mkdirs()
        // Pre-load cache on a background thread at startup
        scope.launch { ensureCacheLoaded() }
    }

    /**
     * Get all user preferences. Returns cached copy — never blocks the caller.
     */
    fun getPreferences(): UserPreferences {
        return cachedPreferences ?: createDefaultPreferences()
    }

    /**
     * Update user preferences. Writes are debounced (500 ms) and run on IO dispatcher.
     * Safe to call from any thread including the main thread.
     */
    fun updatePreferences(preferences: UserPreferences) {
        cachedPreferences = preferences
        scheduleDebouncedFlush()
    }

    /** Ensure the cache is populated (called once at startup on IO thread). */
    private suspend fun ensureCacheLoaded() {
        if (cachedPreferences != null) return
        mutex.withLock {
            if (cachedPreferences != null) return
            cachedPreferences = loadPreferencesFromDisk() ?: createDefaultPreferences()
        }
    }

    /** Debounce: cancel any pending flush and schedule a new one 500 ms out. */
    private fun scheduleDebouncedFlush() {
        pendingFlushJob?.cancel()
        pendingFlushJob = scope.launch {
            delay(500)
            flushToDisk()
        }
    }

    private suspend fun flushToDisk() = mutex.withLock {
        val prefs = cachedPreferences ?: return
        try {
            withContext(Dispatchers.IO) {
                preferencesFile.writeText(json.encodeToString(prefs))
            }
            Timber.i("Updated user preferences")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update preferences")
        }
    }

    /**
     * Add a project location to remembered projects.
     */
    fun rememberProjectLocation(name: String, path: String, tags: List<String> = emptyList()) {
        val prefs = getPreferences()
        val project = RememberedProject(
            name = name,
            path = path,
            tags = tags,
            lastAccessed = System.currentTimeMillis()
        )
        
        val updated = prefs.copy(
            rememberedProjects = (prefs.rememberedProjects.filterNot { it.path == path } + project)
                .sortedByDescending { it.lastAccessed }
        )
        
        updatePreferences(updated)
        Timber.i("Remembered project: $name at $path")
    }

    /**
     * Get remembered projects.
     */
    fun getRememberedProjects(): List<RememberedProject> {
        return getPreferences().rememberedProjects
    }

    /**
     * Update UI preferences.
     */
    fun updateUIPreferences(darkMode: Boolean? = null, theme: String? = null, fontSize: Int? = null) {
        val prefs = getPreferences()
        val ui = prefs.uiPreferences.copy(
            darkMode = darkMode ?: prefs.uiPreferences.darkMode,
            theme = theme ?: prefs.uiPreferences.theme,
            fontSize = fontSize ?: prefs.uiPreferences.fontSize
        )
        
        updatePreferences(prefs.copy(uiPreferences = ui))
    }

    /**
     * Get UI preferences.
     */
    fun getUIPreferences(): UIPreferences {
        return getPreferences().uiPreferences
    }

    /**
     * Record an interaction pattern (e.g., "user often creates Python projects").
     */
    fun recordInteractionPattern(pattern: String, frequency: Int = 1) {
        val prefs = getPreferences()
        val patterns = prefs.interactionPatterns.toMutableMap()
        patterns[pattern] = (patterns[pattern] ?: 0) + frequency
        
        updatePreferences(prefs.copy(interactionPatterns = patterns))
    }

    /**
     * Get interaction patterns.
     */
    fun getInteractionPatterns(): Map<String, Int> {
        return getPreferences().interactionPatterns
    }

    /**
     * Add a custom shortcut/alias.
     */
    fun addShortcut(alias: String, command: String) {
        val prefs = getPreferences()
        val shortcuts = prefs.customShortcuts.toMutableMap()
        shortcuts[alias] = command
        
        updatePreferences(prefs.copy(customShortcuts = shortcuts))
        Timber.i("Added shortcut: $alias -> $command")
    }

    /**
     * Get custom shortcuts.
     */
    fun getShortcuts(): Map<String, String> {
        return getPreferences().customShortcuts
    }

    /**
     * Get a formatted preferences summary.
     */
    fun getPreferencesSummary(): String {
        val prefs = getPreferences()
        
        return buildString {
            appendLine("⚙️ User Preferences")
            appendLine()
            appendLine("UI Settings:")
            appendLine("  • Dark Mode: ${prefs.uiPreferences.darkMode}")
            appendLine("  • Theme: ${prefs.uiPreferences.theme}")
            appendLine("  • Font Size: ${prefs.uiPreferences.fontSize}sp")
            appendLine()
            appendLine("Remembered Projects (${prefs.rememberedProjects.size}):")
            prefs.rememberedProjects.take(5).forEach { project ->
                appendLine("  • ${project.name}: ${project.path}")
            }
            appendLine()
            appendLine("Interaction Patterns:")
            prefs.interactionPatterns.entries.sortedByDescending { it.value }.take(5).forEach { (pattern, count) ->
                appendLine("  • $pattern: $count times")
            }
            if (prefs.customShortcuts.isNotEmpty()) {
                appendLine()
                appendLine("Custom Shortcuts:")
                prefs.customShortcuts.forEach { (alias, command) ->
                    appendLine("  • $alias -> $command")
                }
            }
        }
    }

    /**
     * Create default preferences.
     */
    private fun createDefaultPreferences(): UserPreferences {
        val default = UserPreferences(
            uiPreferences = UIPreferences(
                darkMode = true,
                theme = "forge_dark",
                fontSize = 14
            ),
            rememberedProjects = emptyList(),
            interactionPatterns = emptyMap(),
            customShortcuts = emptyMap()
        )
        
        updatePreferences(default)
        return default
    }

    /**
     * Load preferences from disk (blocking — only call from IO dispatcher).
     */
    private fun loadPreferencesFromDisk(): UserPreferences? {
        return runCatching {
            if (!preferencesFile.exists()) return null
            val content = preferencesFile.readText()
            json.decodeFromString<UserPreferences>(content)
        }.getOrNull()
    }
}

@Serializable
data class UserPreferences(
    val uiPreferences: UIPreferences = UIPreferences(),
    val rememberedProjects: List<RememberedProject> = emptyList(),
    val interactionPatterns: Map<String, Int> = emptyMap(),
    val customShortcuts: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
)

@Serializable
data class UIPreferences(
    val darkMode: Boolean = true,
    val theme: String = "forge_dark",
    val fontSize: Int = 14,
    val compactMode: Boolean = false
)

@Serializable
data class RememberedProject(
    val name: String,
    val path: String,
    val tags: List<String> = emptyList(),
    val lastAccessed: Long = System.currentTimeMillis()
)
