package com.forge.os.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.domain.heartbeat.HealthLevel
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val heartbeatMonitor: HeartbeatMonitor
) : ViewModel() {
    val status = heartbeatMonitor.status

    fun refresh() {
        viewModelScope.launch { heartbeatMonitor.checkNow() }
    }
}

@Composable
fun StatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsState()
    val orange = forgePalette.orange
    val bg = forgePalette.bg
    val surface = forgePalette.surface
    val muted = forgePalette.textMuted

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp)
    ) {
        // Header with back arrow
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("💓", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "SYSTEM STATUS", color = orange, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            HealthBadge(status.overallHealth)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Last check: ${formatTime(status.timestamp)}",
            color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))

        if (status.alerts.isNotEmpty()) {
            status.alerts.forEach { alert ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2a1a0a))
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            alert.message, color = Color(0xFFf59e0b), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(status.components.entries.toList()) { (name, comp) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = surface)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(componentIcon(name), fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                name.uppercase(), color = Color.White, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.weight(1f))
                            HealthBadge(HealthLevel.valueOf(comp.health))
                        }
                        if (comp.metrics.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            comp.metrics.forEach { (k, v) ->
                                Row {
                                    Text(
                                        "  $k: ", color = Color.Gray, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        v, color = Color(0xFFa3a3a3), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        comp.message?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it, color = Color(0xFFf59e0b), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            if (status.recommendations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "RECOMMENDATIONS", color = orange, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    status.recommendations.forEach { rec ->
                        Text(
                            "→ $rec", color = Color.Gray, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = orange)
                ) {
                    Text("↺  REFRESH", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun HealthBadge(level: HealthLevel) {
    val (emoji, color) = when (level) {
        HealthLevel.HEALTHY -> "●" to Color(0xFF22c55e)
        HealthLevel.WARNING -> "●" to Color(0xFFf59e0b)
        HealthLevel.CRITICAL -> "●" to Color(0xFFef4444)
        HealthLevel.DOWN -> "●" to Color(0xFF6b7280)
    }
    Text(
        "$emoji ${level.name}", color = color, fontSize = 12.sp,
        fontFamily = FontFamily.Monospace
    )
}

private fun componentIcon(name: String) = when (name) {
    "storage" -> "💾"
    "memory" -> "🧠"
    "api" -> "🌐"
    "workspace" -> "📁"
    "config" -> "⚙️"
    "cron" -> "⏰"
    else -> "🔧"
}

private fun formatTime(ts: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return fmt.format(java.util.Date(ts))
}