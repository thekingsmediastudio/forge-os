package com.forge.os.presentation.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.ForgeLogo
import com.forge.os.presentation.components.VoiceWaveform
import com.forge.os.presentation.theme.forgePalette

/**
 * Modern full-screen voice mode overlay with glassmorphism design.
 * Features:
 * - Animated waveform visualization
 * - Smooth phase transitions
 * - Ember accent colors
 * - Bottom control bar
 */
@Composable
fun VoiceModeOverlay(
    onDismiss: () -> Unit,
    conversationId: String? = null,
    viewModel: VoiceModeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Request RECORD_AUDIO before entering voice mode
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enterVoiceMode(conversationId)
        else onDismiss()
    }

    LaunchedEffect(conversationId) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) viewModel.enterVoiceMode(conversationId)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Clean up when dismissed
    DisposableEffect(Unit) {
        onDispose { viewModel.exitVoiceMode() }
    }

    Dialog(
        onDismissRequest = { viewModel.exitVoiceMode(); onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(forgePalette.bg)
        ) {
            // Gradient background effect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                forgePalette.bg,
                                forgePalette.surface.copy(alpha = 0.5f),
                                forgePalette.bg
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Header ────────────────────────────────────────────────────
                VoiceModeHeader(
                    onClose = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.exitVoiceMode()
                        onDismiss()
                    }
                )

                Spacer(Modifier.height(24.dp))

                // ── Status Badge ──────────────────────────────────────────────
                VoiceStatusBadge(phase = state.phase)

                Spacer(Modifier.height(32.dp))

                // ── Transcript Card ───────────────────────────────────────────
                AnimatedVisibility(
                    visible = state.transcript.isNotBlank(),
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut() + slideOutVertically { -it / 2 }
                ) {
                    VoiceCard(
                        label = "You said",
                        content = state.transcript,
                        accentColor = forgePalette.info
                    )
                }

                Spacer(Modifier.weight(1f))

                // ── Central Voice Orb ─────────────────────────────────────────
                ModernVoiceOrb(
                    phase = state.phase,
                    rmsLevel = state.rmsLevel,
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.tapOrb()
                    }
                )

                Spacer(Modifier.height(24.dp))

                // ── Waveform ──────────────────────────────────────────────────
                VoiceWaveform(
                    rmsLevel = state.rmsLevel,
                    isActive = state.phase == VoicePhase.LISTENING,
                    barCount = 7
                )

                Spacer(Modifier.weight(1f))

                // ── Response Card ─────────────────────────────────────────────
                AnimatedVisibility(
                    visible = state.agentResponse.isNotBlank(),
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    VoiceCard(
                        label = "Forge",
                        content = state.agentResponse.take(300).let {
                            if (state.agentResponse.length > 300) "$it…" else it
                        },
                        accentColor = forgePalette.orange
                    )
                }

                // ── Error ─────────────────────────────────────────────────────
                AnimatedVisibility(visible = state.error != null) {
                    Surface(
                        color = forgePalette.dangerBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            state.error ?: "",
                            color = forgePalette.danger,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Bottom Controls ───────────────────────────────────────────
                VoiceBottomControls(
                    onEndSession = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.exitVoiceMode()
                        onDismiss()
                    }
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun VoiceModeHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeLogo(size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Voice Mode",
                color = forgePalette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(40.dp)
                .background(forgePalette.surface, CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                "Close",
                tint = forgePalette.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun VoiceStatusBadge(phase: VoicePhase) {
    val (text, color) = when (phase) {
        VoicePhase.LISTENING -> "Listening" to forgePalette.orange
        VoicePhase.THINKING -> "Thinking" to forgePalette.info
        VoicePhase.SPEAKING -> "Speaking" to forgePalette.success
        VoicePhase.IDLE -> "Ready" to forgePalette.textMuted
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pulsing dot
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = alpha), CircleShape)
            )
            Text(
                text,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun VoiceCard(
    label: String,
    content: String,
    accentColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = forgePalette.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                content,
                color = forgePalette.textPrimary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModernVoiceOrb(
    phase: VoicePhase,
    rmsLevel: Float,
    onTap: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Idle pulse
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    // Speaking bounce
    val speakBounce by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "speak_bounce"
    )

    val orbScale = when (phase) {
        VoicePhase.LISTENING -> 1f + rmsLevel * 0.3f
        VoicePhase.THINKING -> idlePulse
        VoicePhase.SPEAKING -> speakBounce
        VoicePhase.IDLE -> idlePulse
    }

    val orbColor = when (phase) {
        VoicePhase.LISTENING -> forgePalette.orange
        VoicePhase.THINKING -> forgePalette.info
        VoicePhase.SPEAKING -> forgePalette.success
        VoicePhase.IDLE -> forgePalette.surface2
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(180.dp)
            .scale(orbScale)
            .clickable(onClick = onTap)
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.3f),
                            orbColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Middle ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(orbColor.copy(alpha = 0.15f), CircleShape)
        )

        // Core circle
        Surface(
            modifier = Modifier.size(100.dp),
            color = orbColor,
            shape = CircleShape,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (phase) {
                        VoicePhase.LISTENING -> Icons.Default.Mic
                        VoicePhase.THINKING -> Icons.Outlined.Psychology
                        VoicePhase.SPEAKING -> Icons.Default.VolumeUp
                        VoicePhase.IDLE -> Icons.Default.Mic
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceBottomControls(onEndSession: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // End session button
        Surface(
            onClick = onEndSession,
            color = forgePalette.dangerBg,
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = null,
                    tint = forgePalette.danger,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "End Session",
                    color = forgePalette.danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
