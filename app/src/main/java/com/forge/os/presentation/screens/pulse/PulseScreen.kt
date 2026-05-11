package com.forge.os.presentation.screens.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.heartbeat.HealthLevel
import com.forge.os.presentation.theme.LocalForgePalette

/**
 * Phase P — Pulse Dashboard.
 * Real-time monitoring of agent health, costs, and active tasks.
 */
@Composable
fun PulseScreen(
    onBack: () -> Unit,
    viewModel: PulseViewModel = hiltViewModel()
) {
    val palette = LocalForgePalette.current
    val state by viewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = palette.textMuted, modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "📈  SYSTEM PULSE", color = palette.orange, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Refresh, "Refresh",
                    tint = palette.textMuted, modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Overall Status
            item {
                StatusCard(state.system.overallHealth, palette)
            }

            // Financial Pulse
            item {
                BudgetCard(
                    spend = state.dailySpend,
                    limit = state.dailyLimit,
                    enabled = state.budgetEnabled,
                    palette = palette
                )
            }

            // Task Pulse
            item {
                TaskPulseCard(
                    cronCount = state.activeCronCount,
                    agentCount = state.activeAgentCount,
                    palette = palette
                )
            }

            // Component Health Grid
            item {
                Text(
                    "COMPONENT STATUS",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            state.system.components.forEach { (name, status) ->
                item {
                    ComponentCard(name, status, palette)
                }
            }

            if (state.system.alerts.isNotEmpty()) {
                item {
                    Text(
                        "ACTIVE ALERTS",
                        color = Color(0xFFb91c1c),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(state.system.alerts.size) { i ->
                    val alert = state.system.alerts[i]
                    AlertCard(alert.component, alert.message, palette)
                }
            }

            // Background Activity Log
            item {
                Text(
                    "BACKGROUND ACTIVITY",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }

            if (state.backgroundLogs.isEmpty()) {
                item {
                    Text(
                        "No recent activity logged.",
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(state.backgroundLogs.size) { i ->
                    LogItemCard(state.backgroundLogs[i], palette)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatusCard(health: HealthLevel, palette: com.forge.os.presentation.theme.ForgePalette) {
    val color = when (health) {
        HealthLevel.HEALTHY -> Color(0xFF15803d)
        HealthLevel.WARNING -> palette.orange
        HealthLevel.CRITICAL -> Color(0xFFb91c1c)
        HealthLevel.DOWN -> Color.Gray
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(24.dp)
            ) {
                // Glow effect
                Box(
                    Modifier
                        .size(16.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .background(color.copy(alpha = alpha), RoundedCornerShape(8.dp))
                )
                // Solid dot
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(5.dp))
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "SYSTEM STATUS: ${health.name}",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
                Text(
                    if (health == HealthLevel.HEALTHY) "All systems operational." 
                    else "Monitoring anomalies in core services.",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(
    spend: Double,
    limit: Double,
    enabled: Boolean,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    val progress = if (limit > 0) (spend / limit).coerceIn(0.0, 1.0).toFloat() else 0f
    val barColor = if (progress > 0.9f) Color(0xFFb91c1c) else if (progress > 0.7f) palette.orange else Color(0xFF15803d)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "TOKEN BUDGET",
                color = palette.textMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$${"%.2f".format(spend)}",
                    color = palette.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "/ $${"%.2f".format(limit)} USD",
                    color = palette.textMuted,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = barColor,
                trackColor = Color(0xFF1f1f1f)
            )
            if (enabled && spend >= limit) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ ECO-MODE ACTIVE: Budget exceeded.",
                    color = palette.orange,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun TaskPulseCard(
    cronCount: Int,
    agentCount: Int,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("CRON JOBS", color = palette.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "$cronCount ACTIVE",
                    color = if (cronCount > 0) palette.orange else palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("SUB-AGENTS", color = palette.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "$agentCount RUNNING",
                    color = if (agentCount > 0) palette.orange else palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ComponentCard(
    name: String,
    status: com.forge.os.domain.heartbeat.ComponentStatus,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    val healthColor = when (status.health) {
        HealthLevel.HEALTHY.name -> Color(0xFF15803d)
        HealthLevel.WARNING.name -> palette.orange
        HealthLevel.CRITICAL.name -> Color(0xFFb91c1c)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(healthColor, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    name.uppercase(),
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    status.health,
                    color = healthColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (status.metrics.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                status.metrics.forEach { (k, v) ->
                    Row {
                        Text("$k:", color = palette.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(4.dp))
                        Text(v, color = palette.textPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (status.message != null) {
                Spacer(Modifier.height(4.dp))
                Text(status.message!!, color = palette.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun AlertCard(
    component: String,
    message: String,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x33b91c1c)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFb91c1c)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                component.uppercase(),
                color = Color(0xFFef4444),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                message,
                color = palette.textPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun LogItemCard(
    log: com.forge.os.domain.debug.BackgroundTaskLog,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    val statusColor = if (log.success) Color(0xFF15803d) else Color(0xFFb91c1c)
    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.source.name,
                    color = palette.orange,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    log.label,
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    timeStr,
                    color = palette.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(statusColor, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (log.success) "SUCCESS" else "FAILED",
                    color = statusColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (log.durationMs > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${log.durationMs}ms",
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (!log.success && log.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    log.error,
                    color = Color(0xFFef4444),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else if (log.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    log.output.take(120).replace("\n", " "),
                    color = palette.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}
