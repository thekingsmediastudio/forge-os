package com.forge.os.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Per-theme palette for the bespoke "Forge OS" terminal look used by the
 * Settings, Chat, Diagnostics and Status screens (and the shared
 * [com.forge.os.presentation.screens.common.ForgeOsPalette]).
 *
 * The previous build hard-coded a single dark palette as file-level constants
 * inside each screen, which made the theme switcher in Settings effectively a
 * no-op for everything except the few Material-coloured surfaces. This palette
 * is provided through a CompositionLocal so a screen can fetch the right
 * variant for the active theme via [forgePalette].
 */
data class ForgePalette(
    val orange: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val success: Color,
    val successBg: Color,
    val danger: Color,
    val dangerBg: Color,
    val info: Color,
    val neuralPulse: Color,
    val thinking: Color,
)

val ForgeDarkPalette = ForgePalette(
    orange = Color(0xFFFF4500),
    bg = Color(0xFF0a0a0a),
    surface = Color(0xFF111111),
    surface2 = Color(0xFF1a1a1a),
    border = Color(0xFF333333),
    textPrimary = Color(0xFFe5e5e5),
    textMuted = Color(0xFF737373),
    textDim = Color(0xFF404040),
    success = Color(0xFF4ade80),
    successBg = Color(0xFF052e16),
    danger = Color(0xFFef4444),
    dangerBg = Color(0xFF1a0a0a),
    info = Color(0xFF818cf8),
    neuralPulse = Color(0xFFFF4500),
    thinking = Color(0xFFfbbf24),
)

val ForgeLightPalette = ForgePalette(
    orange = Color(0xFFD32F2F),
    bg = Color(0xFFfafafa),
    surface = Color(0xFFffffff),
    surface2 = Color(0xFFf3f4f6),
    border = Color(0xFFd4d4d8),
    textPrimary = Color(0xFF111827),
    textMuted = Color(0xFF52525b),
    textDim = Color(0xFF9ca3af),
    success = Color(0xFF15803d),
    successBg = Color(0xFFdcfce7),
    danger = Color(0xFFb91c1c),
    dangerBg = Color(0xFFfee2e2),
    info = Color(0xFF4338ca),
    neuralPulse = Color(0xFFD32F2F),
    thinking = Color(0xFFd97706),
)

/**
 * CompositionLocal that screens read to obtain the active palette. Defaults
 * to the dark palette so non-themed previews still render sensibly.
 */
val LocalForgePalette = staticCompositionLocalOf { ForgeDarkPalette }

/** Convenience accessor for use inside @Composable functions. */
val forgePalette: ForgePalette
    @Composable
    @ReadOnlyComposable
    get() = LocalForgePalette.current
