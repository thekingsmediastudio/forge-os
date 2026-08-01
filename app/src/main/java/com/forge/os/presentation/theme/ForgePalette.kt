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
    /** Overlay scrim used behind dialogs / side menus. */
    val scrim: Color,
)

val ForgeDarkPalette = ForgePalette(
    orange = Color(0xFFFF6B35),
    bg = Color(0xFF0B0B0F),
    surface = Color(0xFF141419),
    surface2 = Color(0xFF1D1D24),
    border = Color(0xFF2A2A32),
    textPrimary = Color(0xFFF2F2F4),
    textMuted = Color(0xFF9A9AA3),
    textDim = Color(0xFF5C5C66),
    success = Color(0xFF34D399),
    successBg = Color(0xFF0A2E22),
    danger = Color(0xFFF87171),
    dangerBg = Color(0xFF2E1216),
    info = Color(0xFF60A5FA),
    neuralPulse = Color(0xFFFF6B35),
    thinking = Color(0xFFFBBF24),
    scrim = Color(0xCC000000),
)

val ForgeLightPalette = ForgePalette(
    orange = Color(0xFFE85A2A),
    bg = Color(0xFFF7F7F9),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F0F4),
    border = Color(0xFFE2E2E8),
    textPrimary = Color(0xFF17171C),
    textMuted = Color(0xFF5B5B66),
    textDim = Color(0xFFA0A0AB),
    success = Color(0xFF059669),
    successBg = Color(0xFFD1FAE5),
    danger = Color(0xFFDC2626),
    dangerBg = Color(0xFFFEE2E2),
    info = Color(0xFF2563EB),
    neuralPulse = Color(0xFFE85A2A),
    thinking = Color(0xFFD97706),
    scrim = Color(0x4D000000),
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
