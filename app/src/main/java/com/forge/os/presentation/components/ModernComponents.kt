package com.forge.os.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.R
import com.forge.os.presentation.theme.forgePalette

// Modern color palette - now using theme system
val ModernBg: Color
    @Composable @ReadOnlyComposable get() = forgePalette.bg
val ModernSurface: Color
    @Composable @ReadOnlyComposable get() = forgePalette.surface
val ModernSurfaceHover: Color
    @Composable @ReadOnlyComposable get() = forgePalette.surface2
val ModernAccent: Color
    @Composable @ReadOnlyComposable get() = forgePalette.orange
val ModernAccentHover: Color
    @Composable @ReadOnlyComposable get() = forgePalette.orange.copy(alpha = 0.8f)
val ModernTextPrimary: Color
    @Composable @ReadOnlyComposable get() = forgePalette.textPrimary
val ModernTextSecondary: Color
    @Composable @ReadOnlyComposable get() = forgePalette.textMuted
val ModernBorder: Color
    @Composable @ReadOnlyComposable get() = forgePalette.border
val ModernSuccess: Color
    @Composable @ReadOnlyComposable get() = forgePalette.success
val ModernWarning: Color
    @Composable @ReadOnlyComposable get() = forgePalette.thinking
val ModernError: Color
    @Composable @ReadOnlyComposable get() = forgePalette.danger

/**
 * Forge OS Logo Component
 * Uses actual PNG from resources instead of text
 */
@Composable
fun ForgeLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    animated: Boolean = false
) {
    if (animated) {
        val scale by rememberInfiniteTransition(label = "logo_scale").animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Forge OS",
            modifier = modifier
                .size(size)
                .scale(scale)
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Forge OS",
            modifier = modifier.size(size)
        )
    }
}

/**
 * Modern Header Component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernHeader(
    title: String,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackClick != null) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        "Back",
                        tint = ModernTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = ModernTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = ModernTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            
            actions()
        }
    }
}

/**
 * Modern Card Component
 */
@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * Modern Button Component
 */
enum class ButtonVariant {
    Primary, Secondary, Outline, Ghost
}

@Composable
fun ModernButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true
) {
    val colors = when (variant) {
        ButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = ModernAccent,
            contentColor = Color.White
        )
        ButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = ModernSurface,
            contentColor = ModernTextPrimary
        )
        ButtonVariant.Outline -> ButtonDefaults.outlinedButtonColors(
            contentColor = ModernTextPrimary
        )
        ButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = ModernTextPrimary
        )
    }
    
    when (variant) {
        ButtonVariant.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            shape = RoundedCornerShape(12.dp)
        ) {
            ButtonContent(icon, text)
        }
        ButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors
        ) {
            ButtonContent(icon, text)
        }
        else -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            shape = RoundedCornerShape(12.dp)
        ) {
            ButtonContent(icon, text)
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector?, text: String) {
    if (icon != null) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 14.sp)
}

/**
 * Modern TextField Component
 */
@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        modifier = modifier,
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp)) }
        } else null,
        singleLine = singleLine,
        maxLines = maxLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ModernAccent,
            unfocusedBorderColor = ModernBorder,
            focusedTextColor = ModernTextPrimary,
            unfocusedTextColor = ModernTextPrimary,
            focusedLabelColor = ModernAccent,
            unfocusedLabelColor = ModernTextSecondary,
            cursorColor = ModernAccent
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

/**
 * Status Badge Component
 */
@Composable
fun StatusBadge(
    status: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            status,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Loading State Component
 */
@Composable
fun LoadingState(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = ModernAccent,
                modifier = Modifier.size(48.dp)
            )
            Text(
                message,
                color = ModernTextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Empty State Component
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = ModernTextSecondary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                title,
                color = ModernTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                color = ModernTextSecondary,
                fontSize = 14.sp
            )
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}

/**
 * Section Header Component
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = ModernTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        if (action != null) {
            action()
        }
    }
}

/**
 * Animated Gradient Background
 */
@Composable
fun AnimatedGradientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ModernBg,
                        ModernAccent.copy(alpha = 0.1f * offset),
                        ModernBg
                    )
                )
            )
    )
}
