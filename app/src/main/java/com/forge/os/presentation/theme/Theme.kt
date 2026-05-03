package com.forge.os.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ForgeOrange,
    secondary = ForgeBlue,
    tertiary = ForgeGreen,
    background = ForgeSurface,
    surface = ForgeSurfaceVariant,
    onBackground = ForgeOnSurface,
    onSurface = ForgeOnSurface,
    onSurfaceVariant = ForgeOnSurfaceVariant,
    error = ForgeError,
    onPrimary = ForgeOnSurface
)

private val LightColorScheme = lightColorScheme(
    primary = ForgeOrange,
    secondary = ForgeBlue,
    tertiary = ForgeGreen,
    background = ForgeLightSurface,
    surface = ForgeLightSurfaceVariant,
    onBackground = ForgeLightOnSurface,
    onSurface = ForgeLightOnSurface,
    onSurfaceVariant = ForgeLightOnSurfaceVariant,
    error = ForgeError,
    onPrimary = ForgeOnSurface
)

@Composable
fun ForgeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val palette = if (darkTheme) ForgeDarkPalette else ForgeLightPalette
    CompositionLocalProvider(LocalForgePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ForgeTypography,
            content = content
        )
    }
}
