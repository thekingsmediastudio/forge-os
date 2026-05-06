package com.forge.os.presentation.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.heartbeat.HealthLevel
import com.forge.os.presentation.components.*

/**
 * Modern system status dashboard with real-time health monitoring.
 * Features:
 * - Card-based layout
 * - Animated status indicators
 * - Color-coded health levels
 * - Real-time metrics
 * - Quick actions
 */
@Composable
fun ModernStatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModernBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Modern Header
            ModernHeader(
                title = "System Status",
                subtitle = "Last check: ${formatTime(status.timestamp)}",
                onBackClick = onNavigateBack
            ) {
                // Overall health badge
                HealthStatusBadge(status.overallHealth)
            }
            
            // Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Alerts section
                if (status.alerts.isNotEmpty()) {
                    item {
                        AlertsSection(alerts = status.alerts)
                    }
                }
                
                // Overall health card
                item {
                    OverallHealthCard(
                        health = status.overallHealth,
                        onRefresh = { viewModel.refresh() }
                    )
                }
                
                // Components section
                item {
                    SectionHeader(title = "COMPONENTS")
                }
                
                items(status.components.entries.toList()) { (name, component) ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 }
                    ) {
                        ComponentCard(
                            name = name,
                            health = HealthLevel.valueOf(component.health),
                            metrics = component.metrics,
                            message = component.message
                        )
                    }
                }
                
                // Recommendations section
                if (status.recommendations.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(title = "RECOMMENDATIONS")
                    }
                    
                    item {
                        RecommendationsCard(recommendations = status.recommendations)
                    }
                }
                
                // Refresh button
                item {
                    Spacer(Modifier.height(8.dp))
                    ModernButton(
                        text = "Refresh Status",
                        onClick = { viewModel.refresh() },
                        icon = Icons.Outlined.Refresh,
                        variant = ButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthStatusBadge(health: HealthLevel) {
    val (color, text) = getHealthColorAndText(health)
    
    // Animated pulse for non-healthy states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (health == HealthLevel.HEALTHY) 1f else 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
            Text(
                text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun getHealthColorAndText(health: HealthLevel): Pair<Color, String> {
    return when (health) {
        HealthLevel.HEALTHY -> ModernSuccess to "Healthy"
        HealthLevel.WARNING -> ModernWarning to "Warning"
        HealthLevel.CRITICAL -> ModernError to "Critical"
        HealthLevel.DOWN -> ModernTextSecondary to "Down"
    }
}

@Composable
private fun AlertsSection(alerts: List<com.forge.os.domain.heartbeat.AlertInfo>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.forEach { alert ->
            Surface(
                color = ModernWarning.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ModernWarning.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = ModernWarning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        alert.message,
                        color = ModernTextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun OverallHealthCard(
    health: HealthLevel,
    onRefresh: () -> Unit
) {
    ModernCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Overall Health",
                    color = ModernTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, color, text) = when (health) {
                        HealthLevel.HEALTHY -> Triple(Icons.Outlined.CheckCircle, ModernSuccess, "All Systems Operational")
                        HealthLevel.WARNING -> Triple(Icons.Outlined.Warning, ModernWarning, "Some Issues Detected")
                        HealthLevel.CRITICAL -> Triple(Icons.Outlined.Error, ModernError, "Critical Issues")
                        HealthLevel.DOWN -> Triple(Icons.Outlined.Cancel, ModernTextSecondary, "System Down")
                    }
                    
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(32.dp)
                    )
                    
                    Text(
                        text,
                        color = ModernTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(40.dp)
                    .background(ModernAccent.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = ModernAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ComponentCard(
    name: String,
    health: HealthLevel,
    metrics: Map<String, String>,
    message: String?
) {
    val (icon, iconColor) = getComponentIcon(name)
    val healthColor = when (health) {
        HealthLevel.HEALTHY -> ModernSuccess
        HealthLevel.WARNING -> ModernWarning
        HealthLevel.CRITICAL -> ModernError
        HealthLevel.DOWN -> ModernTextSecondary
    }
    
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(iconColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Column {
                        Text(
                            name.uppercase(),
                            color = ModernTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            health.name,
                            color = healthColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(healthColor)
                )
            }
            
            // Metrics
            if (metrics.isNotEmpty()) {
                Divider(color = ModernBorder)
                
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    metrics.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                key,
                                color = ModernTextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                value,
                                color = ModernTextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            // Message
            if (message != null) {
                Surface(
                    color = ModernWarning.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        message,
                        color = ModernWarning,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationsCard(recommendations: List<String>) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = ModernAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Recommendations",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recommendations.forEach { recommendation ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .offset(y = 6.dp)
                                .background(ModernAccent, CircleShape)
                        )
                        Text(
                            recommendation,
                            color = ModernTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private fun getComponentIcon(name: String): Pair<ImageVector, Color> {
    return when (name.lowercase()) {
        "storage" -> Icons.Outlined.Storage to Color(0xFF8B5CF6)
        "memory" -> Icons.Outlined.Memory to Color(0xFFEC4899)
        "api" -> Icons.Outlined.Api to Color(0xFF3B82F6)
        "workspace" -> Icons.Outlined.Folder to Color(0xFF10B981)
        "config" -> Icons.Outlined.Settings to Color(0xFFF59E0B)
        "cron" -> Icons.Outlined.Schedule to Color(0xFF06B6D4)
        "database" -> Icons.Outlined.Storage to Color(0xFF8B5CF6)
        "network" -> Icons.Outlined.Wifi to Color(0xFF3B82F6)
        "security" -> Icons.Outlined.Security to Color(0xFFEF4444)
        else -> Icons.Outlined.Build to ModernAccent
    }
}

private fun formatTime(ts: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return fmt.format(java.util.Date(ts))
}
