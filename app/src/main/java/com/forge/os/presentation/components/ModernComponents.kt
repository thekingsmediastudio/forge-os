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
            painter = painterResource(id = R.drawable.ic_forge_logo),
            contentDescription = "Forge OS",
            modifier = modifier
                .size(size)
                .scale(scale)
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_forge_logo),
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

// ── Drawer Components (Quiet Power) ─────────────────────────────────────────

/**
 * Drawer header — logo + app name + subtitle with bottom divider.
 */
@Composable
fun DrawerHeader() {
    Column {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ForgeLogo(size = 38.dp)
            Column {
                Text(
                    "Forge OS",
                    color = ModernTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "AI Development",
                    color = ModernTextSecondary,
                    fontSize = 12.sp
                )
            }
        }
        HorizontalDivider(
            color = forgePalette.divider,
            thickness = 0.5.dp
        )
    }
}

/**
 * Drawer section label — uppercase, muted, letter-spaced.
 */
@Composable
fun DrawerSection(label: String) {
    Text(
        label,
        color = forgePalette.textDim.copy(alpha = 0.6f),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/**
 * Drawer navigation item with optional active state.
 * Active: ember left-border + subtle ember tint background.
 */
@Composable
fun DrawerItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (isActive) ModernTextPrimary else forgePalette.textMuted.copy(alpha = 0.7f)
    val iconColor = if (isActive) ModernAccent else forgePalette.textMuted.copy(alpha = 0.5f)

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Box {
            // Active gradient background
            if (isActive) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    forgePalette.orange.copy(alpha = 0.10f),
                                    forgePalette.orange.copy(alpha = 0.02f),
                                )
                            )
                        )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ember left-border indicator
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(
                            if (isActive) ModernAccent else Color.Transparent
                        )
                )
                Spacer(Modifier.width(22.dp))
                Icon(
                    icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Simple header for secondary screens — back arrow + title + optional subtitle.
 * No surface background or shadow; sits directly on the screen background.
 */
@Composable
fun SimpleHeader(
    title: String,
    subtitle: String? = null,
    onBackClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.size(40.dp)
        ) {
            ForgeLogo(size = 28.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = ModernTextPrimary,
                fontSize = 16.sp,
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

// ═══════════════════════════════════════════════════════════════════════════════
// UI-7: Polish & Micro-interactions
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Pressable card with scale animation on press
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = ModernSurface,
    borderColor: Color = ModernBorder,
    cornerRadius: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 4.dp,
        animationSpec = tween(150),
        label = "cardElevation"
    )

    Card(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                isPressed = true
                onClick()
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(cornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Animated button with color transition on press
 */
@Composable
fun AnimatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = ModernAccent,
    pressedColor: Color = ModernAccentHover,
    contentColor: Color = Color.White,
    cornerRadius: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> backgroundColor.copy(alpha = 0.5f)
            isPressed -> pressedColor
            else -> backgroundColor
        },
        animationSpec = tween(150),
        label = "buttonColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    Button(
        onClick = {
            isPressed = true
            onClick()
        },
        modifier = modifier.scale(scale),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = contentColor,
            disabledContainerColor = backgroundColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(cornerRadius),
        content = content
    )
}

/**
 * Typing indicator with animated dots
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = ModernTextSecondary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor.copy(alpha = alpha), CircleShape)
            )
        }
    }
}

/**
 * Pulsing badge for status indicators
 */
@Composable
fun PulsingBadge(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/**
 * Shimmer loading placeholder
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Box(
        modifier = modifier
            .background(
                ModernSurfaceHover.copy(alpha = alpha),
                RoundedCornerShape(cornerRadius)
            )
    )
}

/**
 * Animated checkmark for success states
 */
@Composable
fun AnimatedCheckmark(
    modifier: Modifier = Modifier,
    color: Color = ModernSuccess,
    size: Dp = 24.dp
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(),
        modifier = modifier
    ) {
        Text(
            "✓",
            color = color,
            fontSize = (size.value * 0.8).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Shake animation modifier for error states
 */
@Composable
fun Modifier.shake(enabled: Boolean): Modifier {
    val offset by animateFloatAsState(
        targetValue = if (enabled) 0f else 0f,
        animationSpec = if (enabled) {
            keyframes {
                durationMillis = 400
                0f at 0
                (-10f) at 50
                10f at 100
                (-8f) at 150
                8f at 200
                (-5f) at 250
                5f at 300
                0f at 400
            }
        } else {
            tween(0)
        },
        label = "shake"
    )
    return this.then(Modifier.offset(x = offset.dp))
}

/**
 * Fade in animation for content appearing
 */
@Composable
fun FadeInContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)) + 
                expandVertically(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200)) +
               shrinkVertically(animationSpec = tween(200)),
        modifier = modifier
    ) {
        content()
    }
}
