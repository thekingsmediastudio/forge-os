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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.LocalForgePalette

/**
 * Shared "Forge OS" terminal palette accessor for all the Phase D module
 * screens. Each property reads from the active [LocalForgePalette], so
 * flipping the theme switcher in Settings repaints these screens too.
 *
 * The original implementation hard-coded a single dark palette as constants,
 * which is what made the theme switcher feel broken — toggling it re-rendered
 * the Material wrapper but every screen kept drawing dark hex literals.
 */
object ForgeOsPalette {
    val Orange: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.orange
    val Bg: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.bg
    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface
    val Surface2: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface2
    val Border: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.border
    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
    val TextMuted: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textMuted
    val TextDim: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textDim
    val Success: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.success
    val SuccessBg: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.successBg
    val Danger: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.danger
    val DangerBg: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.dangerBg
    val Info: Color
        @Composable @ReadOnlyComposable get() = LocalForgePalette.current.info
}

@Composable
fun ModuleScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ForgeOsPalette.Bg)) {
        Row(
            Modifier.fillMaxWidth().background(ForgeOsPalette.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(title, color = ForgeOsPalette.Orange, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun StatusPill(text: String, color: Color, bg: Color) {
    Box(
        Modifier.background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
