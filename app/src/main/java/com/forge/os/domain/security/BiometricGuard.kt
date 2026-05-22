package com.forge.os.domain.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.forge.os.domain.user.UserPreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesManager: UserPreferencesManager
) {
    fun isEnabled(): Boolean = userPreferencesManager.getPreferences().securityPreferences.biometricLockEnabled

    fun isScreenLocked(route: String): Boolean {
        if (!isEnabled()) return false
        val locked = userPreferencesManager.getPreferences().securityPreferences.lockedScreens
        return locked.contains(route) || locked.contains("all")
    }

    /**
     * Attempts to authenticate the user using biometrics or system credentials.
     * @param activity The host activity (must be FragmentActivity).
     * @param title Title for the biometric prompt.
     * @param onResult Callback yielding true on success, false on failure/cancel.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Authentication Required",
        onResult: (Boolean) -> Unit
    ) {
        val biometricManager = BiometricManager.from(activity)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric capability or not enrolled — consider authenticated if no hardware
            // Or we could force a PIN fallback here.
            onResult(true)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onResult(false)
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Confirm your identity to access this module")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
