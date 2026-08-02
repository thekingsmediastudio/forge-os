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
import com.forge.os.presentation.components.spotlightTarget
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
    
    // Tutorial state
    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
    val tutorialManager = tutorialVm.tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    
    // Check if tutorial should be shown
    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowTutorial(com.forge.os.domain.tutorial.TutorialType.STATUS)) {
            kotlinx.coroutines.delay(500)
            showTutorial = true
        }
    }

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
                "SYSTEM STATUS", color = orange, fontSize = 16.sp, letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.spotlightTarget("status_health")) {
                HealthBadge(status.overallHealth)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Last check: ${formatTime(status.timestamp)}",
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))

        if (status.alerts.isNotEmpty()) {
            status.alerts.forEach { alert ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = forgePalette.warningBg)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            alert.message, color = forgePalette.warning, fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.spotlightTarget("status_components")
        ) {
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
                                name.uppercase(), color = Color.White, fontSize = 13.sp
                            )
                            Spacer(Modifier.weight(1f))
                            HealthBadge(HealthLevel.valueOf(comp.health))
                        }
                        if (comp.metrics.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            comp.metrics.forEach { (k, v) ->
                                Row {
                                    Text(
                                        "  $k: ", color = Color.Gray, fontSize = 11.sp
                                    )
                                    Text(
                                        v, color = forgePalette.textMuted, fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        comp.message?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it, color = forgePalette.warning, fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            if (status.recommendations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "RECOMMENDATIONS", color = orange, fontSize = 12.sp, letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    status.recommendations.forEach { rec ->
                        Text(
                            "→ $rec", color = Color.Gray, fontSize = 11.sp,
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
                    Text("↺  REFRESH", fontSize = 13.sp)
                }
            }
        }
    }
    
    // Tutorial Overlay
    if (showTutorial) {
        val tutorialSteps = listOf(
            com.forge.os.presentation.components.CoachMarkStep(
                title = "System Status",
                description = "Monitor the health of all Forge OS components in real-time.",
                targetKey = null,
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Health Badge",
                description = "Overall system health: HEALTHY (green), WARNING (yellow), CRITICAL (red), or DOWN (gray).",
                targetKey = "status_health",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Component Status",
                description = "Individual status for storage, memory, API, workspace, and other components.",
                targetKey = "status_components",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
            )
        )
        
        com.forge.os.presentation.components.CoachMarkOverlay(
            steps = tutorialSteps,
            currentStep = tutorialStep,
            onNext = { tutorialStep++ },
            onSkip = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.STATUS)
            },
            onDone = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.STATUS)
            }
        )
    }
}

@Composable
fun HealthBadge(level: HealthLevel) {
    val (emoji, color) = when (level) {
        HealthLevel.HEALTHY -> "●" to forgePalette.success
        HealthLevel.WARNING -> "●" to forgePalette.warning
        HealthLevel.CRITICAL -> "●" to forgePalette.danger
        HealthLevel.DOWN -> "●" to forgePalette.textDim
    }
    Text(
        "$emoji ${level.name}", color = color, fontSize = 12.sp
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