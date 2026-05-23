package com.forge.os.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.forge.os.presentation.theme.ForgeTokens
import com.forge.os.presentation.theme.ForgeTokens.Colors
import com.forge.os.presentation.theme.ForgeTokens.Shape
import com.forge.os.presentation.theme.ForgeTokens.Size
import com.forge.os.presentation.theme.ForgeTokens.Spacing
import com.forge.os.presentation.theme.ForgeTokens.Type

// ─────────────────────────────────────────────────────────────────────────────
//  FORGE OS — SHARED COMPONENT LIBRARY
//  Converts all repeating patterns found across 46 CSS/JS screens into
//  reusable Compose composables.
// ─────────────────────────────────────────────────────────────────────────────

/* ══════════════════════════════════════════════════════════════════════════
   1. ANIMATED BACKGROUND GLOWS
   Equivalent to the 3-orb pattern pasted in 37 JS files.
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeBackgroundGlows(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glows")

    val orb1Alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            tween(20000, easing = LinearOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "orb1"
    )
    val orb2Alpha by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            tween(25000, easing = LinearOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "orb2"
    )

    Box(modifier = modifier.fillMaxSize().blur(80.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Main Top-Left Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF4500).copy(alpha = orb1Alpha),
                        Color(0xFFFF6B35).copy(alpha = orb1Alpha * 0.4f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.25f, size.height * 0.3f),
                    radius = size.minDimension * 0.9f
                ),
                radius = size.minDimension * 0.9f,
                center = Offset(size.width * 0.25f, size.height * 0.3f)
            )
            // Accent Bottom-Right Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF6B35).copy(alpha = orb2Alpha),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.8f, size.height * 0.8f),
                    radius = size.minDimension * 0.8f
                ),
                radius = size.minDimension * 0.8f,
                center = Offset(size.width * 0.8f, size.height * 0.8f)
            )
        }
    }
}

/** Wraps any screen content with the animated glow background. */
@Composable
fun ForgeScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Colors.BgBase)
    ) {
        ForgeBackgroundGlows()
        content()
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   2. TOP BAR
   Replaces the 40-file repeated back-button + title + subtitle header.
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeTopBar(
    title: String,
    subtitle: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .border(
                width = Dp.Hairline,
                color = Colors.Border,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null && onMenu == null) {
            ForgeIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onBack,
                contentDescription = "Back"
            )
        } else if (onMenu != null) {
            ForgeIconButton(
                icon = Icons.Outlined.Menu,
                onClick = onMenu,
                contentDescription = "Menu"
            )
        }

        if (titleContent != null) {
            Box(modifier = Modifier.weight(1f)) {
                titleContent()
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Colors.TextPrimary,
                    fontSize = Type.label2,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = Colors.Accent,
                        fontSize = Type.caption,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        actions()
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   3. FROSTED FOOTER BAR
   Replaces the 12-file repeated frosted bottom status bar.
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeFooterBar(
    leftContent: @Composable RowScope.() -> Unit = {},
    rightText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .border(
                width = Dp.Hairline,
                color = Colors.Border,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = leftContent
        )
        if (rightText != null) {
            Text(
                rightText,
                color = Colors.TextTertiary,
                fontSize = Type.label2,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   4. SECTION HEADER
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeSectionHeader(
    label: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            color = Colors.TextTertiary,
            fontSize = Type.label2,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                color = Colors.Accent,
                fontSize = Type.caption,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   5. GLASS CARD
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    shape: androidx.compose.ui.graphics.Shape = Shape.card,
    borderColor: Color = Colors.Border,
    backgroundColor: Color = Color.White.copy(alpha = 0.02f),
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor, shape)
            .border(Dp.Hairline, borderColor, shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = rememberRipple(color = Colors.Accent.copy(alpha = 0.1f)),
                    onClick = onClick
                ) else Modifier
            )
            .padding(padding),
        content = content
    )
}

/* ══════════════════════════════════════════════════════════════════════════
   6. STATUS BADGE (coloured pill)
   ══════════════════════════════════════════════════════════════════════════ */

enum class ForgeStatus { Working, Paused, Done, Error, Healthy, Warning, Info }

@Composable
fun ForgeStatusBadge(
    status: ForgeStatus,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (status) {
        ForgeStatus.Working -> Colors.Accent    to (label ?: "WORKING")
        ForgeStatus.Paused  -> Colors.Warning   to (label ?: "PAUSED")
        ForgeStatus.Done    -> Colors.TextTertiary to (label ?: "DONE")
        ForgeStatus.Healthy -> Colors.Success   to (label ?: "HEALTHY")
        ForgeStatus.Error   -> Colors.Error     to (label ?: "ERROR")
        ForgeStatus.Warning -> Colors.Warning   to (label ?: "WARNING")
        ForgeStatus.Info    -> Colors.Info      to (label ?: "INFO")
    }
    Box(
        modifier = modifier
            .clip(Shape.full)
            .background(color.copy(alpha = 0.13f))
            .border(Dp.Hairline, color.copy(alpha = 0.27f), Shape.full)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = Type.micro,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
        )
    }
}

/** Simple coloured dot indicator. */
@Composable
fun ForgeStatusDot(
    status: ForgeStatus,
    modifier: Modifier = Modifier,
    size: Dp = Size.statusDot
) {
    val color = when (status) {
        ForgeStatus.Working -> Colors.Accent
        ForgeStatus.Paused  -> Colors.Warning
        ForgeStatus.Done    -> Colors.TextTertiary
        ForgeStatus.Healthy -> Colors.Success
        ForgeStatus.Error   -> Colors.Error
        ForgeStatus.Warning -> Colors.Warning
        ForgeStatus.Info    -> Colors.Info
    }
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), "pulse"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(
                if (status == ForgeStatus.Working || status == ForgeStatus.Healthy)
                    Modifier.graphicsLayer { alpha = pulse }
                else Modifier
            )
    )
}

/* ══════════════════════════════════════════════════════════════════════════
   7. PROGRESS BAR
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeProgressBar(
    value: Float,       // 0f..1f
    modifier: Modifier = Modifier,
    color: Color = Colors.Accent,
    trackColor: Color = Color.White.copy(alpha = 0.05f),
    height: Dp = 4.dp
) {
    val animPct by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Shape.full)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animPct)
                .clip(Shape.full)
                .background(
                    Brush.horizontalGradient(listOf(color, Color(0xFFFF8C00)))
                )
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   8. STAT CARD  (numeric metric block)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeStatCard(
    value: String,
    label: String,
    sublabel: String? = null,
    color: Color = Colors.TextPrimary,
    progress: Float? = null,       // 0f..1f, null = no bar
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier, padding = PaddingValues(20.dp)) {
        Text(
            label.uppercase(),
            color = Colors.TextTertiary,
            fontSize = Type.micro,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            color = color,
            fontSize = Type.headline1,
            fontWeight = FontWeight.Black
        )
        if (sublabel != null) {
            Text(
                sublabel,
                color = Colors.TextTertiary,
                fontSize = Type.caption,
                fontWeight = FontWeight.Bold
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(12.dp))
            ForgeProgressBar(progress)
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   9. LIST ROW  (tappable item row with icon + title + subtitle + meta)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = Colors.Accent,
    metaText: String? = null,
    metaColor: Color = Colors.TextTertiary,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape.lg)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(Shape.md)
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(Size.iconLg)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Colors.TextPrimary,
                fontSize = Type.body3,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = Colors.TextSecondary,
                    fontSize = Type.label1,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (metaText != null) {
            Text(
                metaText,
                color = metaColor,
                fontSize = Type.caption,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        trailing?.invoke()
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   10. ICON BUTTON (square frosted)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    size: Dp = Size.btnIcon,
    iconSize: Dp = Size.iconMd,
    tint: Color = Colors.TextPrimary,
    background: Color = Color.White.copy(alpha = 0.03f),
    borderColor: Color = Colors.Border,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(Shape.md)
            .background(background)
            .border(Dp.Hairline, borderColor, Shape.md)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   11. FAB (Floating Action Button)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeFab(
    onClick: () -> Unit,
    icon: ImageVector = Icons.Default.Add,
    contentDescription: String? = "Action",
    color: Color = Colors.Accent,
    size: Dp = Size.fab,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(color, Color(0xFFFF8C00))
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription,
            tint = Color.White,
            modifier = Modifier.size(Size.iconXl)
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   12. SKELETON / SHIMMER  (loading placeholder)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeSkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    widthFraction: Float = 1f
) {
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        "shimmer"
    )
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(Shape.sm)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Colors.BgSurface2,
                        Colors.BgSurface3,
                        Colors.BgSurface2
                    ),
                    startX = -200f + shimmer * 800f,
                    endX = shimmer * 800f
                )
            )
    )
}

@Composable
fun ForgeSkeletonCard(
    lines: Int = 3,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgeSkeletonLine(height = 18.dp, widthFraction = 0.55f)
            repeat(lines - 1) {
                ForgeSkeletonLine(widthFraction = if (it % 2 == 0) 0.9f else 0.7f)
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   13. EMPTY STATE
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(Shape.xl)
                .background(Color.White.copy(alpha = 0.03f))
                .border(Dp.Hairline, Colors.Border, Shape.xl),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Colors.TextTertiary, modifier = Modifier.size(32.dp))
        }
        Text(title, color = Colors.TextPrimary, fontSize = Type.body1, fontWeight = FontWeight.Bold)
        Text(
            subtitle, color = Colors.TextSecondary, fontSize = Type.body3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        action?.invoke()
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   14. DIVIDER
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeDivider(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    if (label == null) {
        HorizontalDivider(
            modifier = modifier.fillMaxWidth(),
            color = Colors.Border,
            thickness = Dp.Hairline
        )
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Colors.Border)
            Text(
                label,
                color = Colors.TextTertiary,
                fontSize = Type.caption,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = Colors.Border)
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   15. LOADING INDICATOR
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeLoadingIndicator(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            color = Colors.Accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp)
        )
        Text(message, color = Colors.TextSecondary, fontSize = Type.body3)
    }
}
