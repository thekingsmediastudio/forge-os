package com.forge.os.domain.tutorial

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tutorial types for each screen.
 */
enum class TutorialType(val key: String) {
    CHAT("chat_tutorial_shown"),
    HUB("hub_tutorial_shown"),
    SETTINGS("settings_tutorial_shown"),
    STATUS("status_tutorial_shown"),
    CONVERSATIONS("conversations_tutorial_shown"),
    WORKSPACE("workspace_tutorial_shown"),
    COMPANION("companion_tutorial_shown"),
    BROWSER("browser_tutorial_shown")
}

/**
 * Manages tutorial/coach mark state persistence.
 * Tracks which tutorials have been shown to the user.
 */
@Singleton
class TutorialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "forge_tutorial_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }

    /**
     * Check if a tutorial should be shown (first visit to screen).
     */
    fun shouldShowTutorial(type: TutorialType): Boolean {
        return !prefs.getBoolean(type.key, false)
    }

    /**
     * Mark a tutorial as shown.
     */
    fun markTutorialShown(type: TutorialType) {
        prefs.edit().putBoolean(type.key, true).apply()
    }

    /**
     * Reset a specific tutorial.
     */
    fun resetTutorial(type: TutorialType) {
        prefs.edit().putBoolean(type.key, false).apply()
    }

    /**
     * Reset all tutorials (for replay from settings).
     */
    fun resetAllTutorials() {
        val editor = prefs.edit()
        TutorialType.values().forEach { type ->
            editor.putBoolean(type.key, false)
        }
        editor.apply()
    }

    // ── Legacy methods for backward compatibility ─────────────────────────────

    fun shouldShowChatTutorial(): Boolean = shouldShowTutorial(TutorialType.CHAT)
    fun shouldShowHubTutorial(): Boolean = shouldShowTutorial(TutorialType.HUB)
    fun markChatTutorialShown() = markTutorialShown(TutorialType.CHAT)
    fun markHubTutorialShown() = markTutorialShown(TutorialType.HUB)

    // ── Onboarding ────────────────────────────────────────────────────────────

    /**
     * Check if onboarding has been completed.
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * Mark onboarding as completed.
     */
    fun markOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }
}
