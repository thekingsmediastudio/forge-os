package com.forge.os.presentation.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.components.SimpleHeader
import com.forge.os.presentation.theme.forgePalette

/**
 * Shared palette accessor for module screens.
 * Reads from the active [forgePalette] so theme switching works everywhere.
 */
object ForgeOsPalette {
    val Orange: Color
        @Composable @ReadOnlyComposable get() = forgePalette.orange
    val Bg: Color
        @Composable @ReadOnlyComposable get() = forgePalette.bg
    val Surface: Color
        @Composable @ReadOnlyComposable get() = forgePalette.surface
    val Surface2: Color
        @Composable @ReadOnlyComposable get() = forgePalette.surface2
    val SurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = forgePalette.surface2
    val Border: Color
        @Composable @ReadOnlyComposable get() = forgePalette.border
    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = forgePalette.textPrimary
    val TextMuted: Color
        @Composable @ReadOnlyComposable get() = forgePalette.textMuted
    val TextDim: Color
        @Composable @ReadOnlyComposable get() = forgePalette.textDim
    val Success: Color
        @Composable @ReadOnlyComposable get() = forgePalette.success
    val SuccessBg: Color
        @Composable @ReadOnlyComposable get() = forgePalette.successBg
    val Danger: Color
        @Composable @ReadOnlyComposable get() = forgePalette.danger
    val DangerBg: Color
        @Composable @ReadOnlyComposable get() = forgePalette.dangerBg
    val Info: Color
        @Composable @ReadOnlyComposable get() = forgePalette.info
}

/**
 * Modern module scaffold — uses SimpleHeader for consistent navigation.
 */
@Composable
fun ModuleScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(ForgeOsPalette.Bg)) {
        SimpleHeader(
            title = title,
            onBackClick = onBack
        ) {
            actions()
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Modern status pill — clean rounded badge.
 */
@Composable
fun StatusPill(text: String, color: Color, bg: Color) {
    Box(
        Modifier.background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
