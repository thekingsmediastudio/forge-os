package com.forge.os.presentation.theme

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    val displayName: String
        get() = when (this) {
            LIGHT -> "Light"
            DARK -> "Dark"
            SYSTEM -> "Follow system"
        }
}
