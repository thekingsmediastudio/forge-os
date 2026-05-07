package com.forge.os.presentation.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.AgentPersonality
import com.forge.os.domain.agent.PersonalityConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalityViewModel @Inject constructor(
    private val agentPersonality: AgentPersonality
) : ViewModel() {

    private val _activePersonality = MutableStateFlow(agentPersonality.getPersonality())
    val activePersonality: StateFlow<PersonalityConfig> = _activePersonality

    private val _profiles = MutableStateFlow(agentPersonality.listProfiles())
    val profiles: StateFlow<List<String>> = _profiles

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    fun updatePersonality(
        name: String,
        description: String,
        systemPrompt: String,
        traits: List<String>,
        communicationStyle: String,
        customInstructions: String
    ) {
        viewModelScope.launch {
            val current = agentPersonality.getPersonality()
            val updated = current.copy(
                name = name.ifBlank { current.name },
                description = description,
                systemPrompt = systemPrompt,
                traits = traits,
                communicationStyle = communicationStyle,
                customInstructions = customInstructions,
                lastModified = System.currentTimeMillis()
            )
            agentPersonality.updatePersonality(updated)
            _activePersonality.value = updated
            _saveMessage.value = "✅ Personality applied"
            kotlinx.coroutines.delay(3000)
            _saveMessage.value = null
        }
    }

    fun saveProfile(profileName: String) {
        viewModelScope.launch {
            agentPersonality.saveProfile(profileName)
            _profiles.value = agentPersonality.listProfiles()
            _saveMessage.value = "✅ Saved profile: $profileName"
            kotlinx.coroutines.delay(3000)
            _saveMessage.value = null
        }
    }

    fun switchToProfile(profileName: String) {
        viewModelScope.launch {
            val ok = agentPersonality.switchToProfile(profileName)
            if (ok) {
                _activePersonality.value = agentPersonality.getPersonality()
                _saveMessage.value = "✅ Switched to: $profileName"
            } else {
                _saveMessage.value = "❌ Profile not found: $profileName"
            }
            kotlinx.coroutines.delay(3000)
            _saveMessage.value = null
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            val default = agentPersonality.resetToDefault()
            _activePersonality.value = default
            _profiles.value = agentPersonality.listProfiles()
            _saveMessage.value = "✅ Reset to Forge defaults"
            kotlinx.coroutines.delay(3000)
            _saveMessage.value = null
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }
}
