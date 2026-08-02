package com.forge.os.domain.tutorial

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    private val _showChatTutorial = MutableStateFlow(false)
    val showChatTutorial: StateFlow<Boolean> = _showChatTutorial

    private val _showHubTutorial = MutableStateFlow(false)
    val showHubTutorial: StateFlow<Boolean> = _showHubTutorial

    companion object {
        private const val PREFS_NAME = "forge_tutorial_prefs"
        private const val KEY_CHAT_TUTORIAL_SHOWN = "chat_tutorial_shown"
        private const val KEY_HUB_TUTORIAL_SHOWN = "hub_tutorial_shown"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }

    /**
     * Check if chat tutorial should be shown (first visit to chat).
     */
    fun shouldShowChatTutorial(): Boolean {
        return !prefs.getBoolean(KEY_CHAT_TUTORIAL_SHOWN, false)
    }

    /**
     * Check if hub tutorial should be shown (first visit to hub).
     */
    fun shouldShowHubTutorial(): Boolean {
        return !prefs.getBoolean(KEY_HUB_TUTORIAL_SHOWN, false)
    }

    /**
     * Mark chat tutorial as shown.
     */
    fun markChatTutorialShown() {
        prefs.edit().putBoolean(KEY_CHAT_TUTORIAL_SHOWN, true).apply()
        _showChatTutorial.value = false
    }

    /**
     * Mark hub tutorial as shown.
     */
    fun markHubTutorialShown() {
        prefs.edit().putBoolean(KEY_HUB_TUTORIAL_SHOWN, true).apply()
        _showHubTutorial.value = false
    }

    /**
     * Trigger chat tutorial display.
     */
    fun triggerChatTutorial() {
        if (shouldShowChatTutorial()) {
            _showChatTutorial.value = true
        }
    }

    /**
     * Trigger hub tutorial display.
     */
    fun triggerHubTutorial() {
        if (shouldShowHubTutorial()) {
            _showHubTutorial.value = true
        }
    }

    /**
     * Reset all tutorials (for replay from settings).
     */
    fun resetAllTutorials() {
        prefs.edit()
            .putBoolean(KEY_CHAT_TUTORIAL_SHOWN, false)
            .putBoolean(KEY_HUB_TUTORIAL_SHOWN, false)
            .apply()
    }

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
