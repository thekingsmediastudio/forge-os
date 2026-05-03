package com.forge.os.presentation.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OnboardingState(
    val provider: ApiKeyProvider = ApiKeyProvider.OPENAI,
    val apiKey: String = "",
    val busy: Boolean = false,
    val error: String? = null
) {
    val canFinish: Boolean get() = apiKey.trim().length >= 8
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun selectProvider(p: ApiKeyProvider) {
        _state.value = _state.value.copy(provider = p, error = null)
    }

    fun updateKey(k: String) {
        _state.value = _state.value.copy(apiKey = k, error = null)
    }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canFinish) {
            _state.value = s.copy(error = "API key looks too short")
            return
        }
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    secureKeyStore.saveKey(s.provider, s.apiKey.trim())
                    setHasOnboarded(true)
                }
                _state.value = _state.value.copy(busy = false)
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "Could not save key: ${e.message}"
                )
            }
        }
    }

    private fun setHasOnboarded(value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, value).apply()
    }

    companion object {
        const val PREFS = "forge_onboarding"
        const val KEY_DONE = "has_onboarded"

        fun isOnboarded(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
    }
}
