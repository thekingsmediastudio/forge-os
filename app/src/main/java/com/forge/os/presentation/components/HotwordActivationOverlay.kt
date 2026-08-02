package com.forge.os.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.screens.voice.VoiceModeViewModel
import com.forge.os.presentation.screens.voice.VoicePhase
import com.forge.os.presentation.theme.forgePalette
import kotlinx.coroutines.launch

/**
 * Beautiful floating popup that appears when the "Hello Forge" wake word
 * is detected. Slides up from the bottom with a glassmorphism card showing
 * the voice pipeline (listening → thinking → speaking).
 *
 * Design inspired by Google Assistant's activation UX:
 * - Subtle entry animation (not jarring)
 * - Real-time transcript feedback
 * - Animated waveform synced to mic level
 * - Smooth state transitions
 * - Swipe down to dismiss
 */
@Composable
fun HotwordActivationOverlay(
    onDismiss: () -> Unit,
    conversationId: String? = null,
    viewModel: VoiceModeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Enter voice mode when the overlay appears
    LaunchedEffect(conversationId) {
        viewModel.enterVoiceMode(conversationId)
    }

    // Clean up when dismissed
    DisposableEffect(Unit) {
        onDispose { viewModel.exitVoiceMode() }
    }

    Dialog(
        onDismissRequest = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.exitVoiceMode()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // Full-screen container with semi-transparent backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.exitVoiceMode()
                    onDismiss()
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // The actual popup card — slides up from bottom
            var dragOffset by remember { mutableStateOf(0f) }

            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > 200f) {
                                        // Swipe down to dismiss
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.exitVoiceMode()
                                        onDismiss()
                                    }
                                    dragOffset = 0f
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    dragOffset += dragAmount
                                }
                            )
                        },
                    color = forgePalette.surfaceGlass,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 16.dp,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // ── Header ──────────────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ForgeLogo(size = 28.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Hello Forge",
                                    color = forgePalette.textPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.exitVoiceMode()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(forgePalette.surface2, CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "Close",
                                    tint = forgePalette.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Status Badge ────────────────────────────────────
                        HotwordStatusBadge(phase = state.phase)

                        Spacer(Modifier.height(24.dp))

                        // ── Transcript Card ─────────────────────────────────
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.transcript.isNotBlank(),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            HotwordCard(
                                label = "You said",
                                content = state.transcript,
                                accentColor = forgePalette.info
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // ── Central Voice Orb ───────────────────────────────
                        HotwordVoiceOrb(
                            phase = state.phase,
                            rmsLevel = state.rmsLevel,
                            onTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.tapOrb()
                            }
                        )

                        Spacer(Modifier.height(16.dp))

                        // ── Waveform ────────────────────────────────────────
                        VoiceWaveform(
                            rmsLevel = state.rmsLevel,
                            isActive = state.phase == VoicePhase.LISTENING,
                            barCount = 7
                        )

                        Spacer(Modifier.height(16.dp))

                        // ── Response Card ───────────────────────────────────
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.agentResponse.isNotBlank(),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            HotwordCard(
                                label = "Forge",
                                content = state.agentResponse.take(200).let {
                                    if (state.agentResponse.length > 200) "$it…" else it
                                },
                                accentColor = forgePalette.orange
                            )
                        }

                        // ── Error ───────────────────────────────────────────
                        androidx.compose.animation.AnimatedVisibility(visible = state.error != null) {
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

                        Spacer(Modifier.height(8.dp))

                        // ── Hint Text ───────────────────────────────────────
                        Text(
                            when (state.phase) {
                                VoicePhase.LISTENING -> "Listening — tap the orb to submit early"
                                VoicePhase.THINKING -> "Forge is thinking"
                                VoicePhase.SPEAKING -> "Tap the orb to interrupt"
                                VoicePhase.IDLE -> "Tap the orb to start"
                            },
                            color = forgePalette.textMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        // ── Swipe hint ──────────────────────────────────────
                        Text(
                            "↓ Swipe down to dismiss",
                            color = forgePalette.textDim,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HotwordStatusBadge(phase: VoicePhase) {
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
private fun HotwordCard(
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HotwordVoiceOrb(
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

    val targetScale = when (phase) {
        VoicePhase.LISTENING -> 1f + rmsLevel * 0.3f
        VoicePhase.THINKING -> idlePulse
        VoicePhase.SPEAKING -> speakBounce
        VoicePhase.IDLE -> idlePulse
    }
    val orbScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "orb_scale"
    )

    val orbColor = when (phase) {
        VoicePhase.LISTENING -> forgePalette.orange
        VoicePhase.THINKING -> forgePalette.info
        VoicePhase.SPEAKING -> forgePalette.success
        VoicePhase.IDLE -> forgePalette.surface2
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .scale(orbScale)
            .clickable(onClick = onTap)
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(120.dp)
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
                .size(90.dp)
                .background(orbColor.copy(alpha = 0.15f), CircleShape)
        )

        // Core circle
        Surface(
            modifier = Modifier.size(60.dp),
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
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
