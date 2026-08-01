package com.forge.os.presentation.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ForgeOsPalette.Bg)) {
        Row(
            Modifier.fillMaxWidth()
                .background(ForgeOsPalette.Surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onBack,
                color = ForgeOsPalette.Surface2,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back",
                        tint = ForgeOsPalette.TextPrimary, modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = ForgeOsPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = ForgeOsPalette.TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(
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
        Modifier.background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp)
    }
}

/**
 * Shared card container for module screens. Uses the modern 14dp rounding,
 * a subtle 1dp border and soft shadow — consistent with ModernCard.
 */
@Composable
fun ModuleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val base = Modifier
        .fillMaxWidth()
        .then(modifier)
        .background(ForgeOsPalette.Surface, shape)
        .border(1.dp, ForgeOsPalette.Border, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = ForgeOsPalette.Surface,
            shape = shape,
            border = androidx.compose.foundation.BorderStroke(1.dp, ForgeOsPalette.Border),
            shadowElevation = 1.dp,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    } else {
        Column(
            modifier = base.padding(14.dp),
            content = content,
        )
    }
}

/**
 * Section header for module screens — modern, roomy, with a soft divider line.
 */
@Composable
fun ModuleSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = ForgeOsPalette.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        if (action != null) {
            action()
        }
    }
}
