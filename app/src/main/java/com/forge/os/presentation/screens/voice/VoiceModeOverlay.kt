package com.forge.os.presentation.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

private val VoiceBg        = Color(0xFF0A0A0A)
private val VoiceAccent    = Color(0xFFFF4500)   // forge burnt orange
private val VoiceSurface   = Color(0xFF1A1A1A)
private val VoiceText      = Color(0xFFFFFFFF)
private val VoiceTextMuted = Color(0xFF888888)

/**
 * Full-screen voice mode overlay.
 *
 * Shows an animated orb that reacts to mic level, displays the user's
 * transcript and the agent's response, and auto-loops STT → agent → TTS.
 *
 * Usage: show this when the user taps the mic button in the chat header.
 */
@Composable
fun VoiceModeOverlay(
    onDismiss: () -> Unit,
    viewModel: VoiceModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Request RECORD_AUDIO before entering voice mode
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enterVoiceMode()
        else onDismiss()
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) viewModel.enterVoiceMode()
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
            dismissOnClickOutside = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VoiceBg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // ── Top bar ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Voice Mode",
                        color = VoiceTextMuted,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                    IconButton(
                        onClick = { viewModel.exitVoiceMode(); onDismiss() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(VoiceSurface, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, "Close", tint = VoiceTextMuted, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Transcript (what user said) ───────────────────────────────
                AnimatedVisibility(
                    visible = state.transcript.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        state.transcript,
                        color = VoiceTextMuted,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.weight(1f))

                // ── Central orb ───────────────────────────────────────────────
                VoiceOrb(
                    phase = state.phase,
                    rmsLevel = state.rmsLevel,
                    onTap = { viewModel.tapOrb() },
                )

                Spacer(Modifier.weight(1f))

                // ── Status label ──────────────────────────────────────────────
                Text(
                    text = when (state.phase) {
                        VoicePhase.LISTENING -> "Listening…"
                        VoicePhase.THINKING  -> "Thinking…"
                        VoicePhase.SPEAKING  -> "Speaking…"
                        VoicePhase.IDLE      -> "Tap to speak"
                    },
                    color = when (state.phase) {
                        VoicePhase.LISTENING -> VoiceAccent
                        VoicePhase.THINKING  -> Color(0xFF60A5FA)
                        VoicePhase.SPEAKING  -> Color(0xFF34D399)
                        VoicePhase.IDLE      -> VoiceTextMuted
                    },
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                )

                Spacer(Modifier.height(16.dp))

                // ── Agent response ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = state.agentResponse.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        color = VoiceSurface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            state.agentResponse.take(300).let {
                                if (state.agentResponse.length > 300) "$it…" else it
                            },
                            color = VoiceText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                AnimatedVisibility(visible = state.error != null) {
                    Text(
                        state.error ?: "",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * The central animated orb. Reacts to mic level when listening,
 * pulses slowly when thinking, bounces when speaking.
 */
@Composable
private fun VoiceOrb(
    phase: VoicePhase,
    rmsLevel: Float,
    onTap: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Idle / thinking pulse
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "idle_pulse",
    )

    // Speaking bounce
    val speakBounce by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "speak_bounce",
    )

    // Thinking rotation (ring)
    val thinkRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "think_rotation",
    )

    val orbScale = when (phase) {
        VoicePhase.LISTENING -> 1f + rmsLevel * 0.35f   // grows with voice
        VoicePhase.THINKING  -> idlePulse
        VoicePhase.SPEAKING  -> speakBounce
        VoicePhase.IDLE      -> idlePulse
    }

    val orbColor = when (phase) {
        VoicePhase.LISTENING -> VoiceAccent
        VoicePhase.THINKING  -> Color(0xFF3B82F6)
        VoicePhase.SPEAKING  -> Color(0xFF10B981)
        VoicePhase.IDLE      -> VoiceSurface
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp)
            .scale(orbScale)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(orbColor.copy(alpha = 0.25f), Color.Transparent),
                ),
                shape = CircleShape,
            )
            .clickable { onTap() },
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(orbColor.copy(alpha = 0.12f), CircleShape)
        )

        // Core circle
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(orbColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (phase) {
                    VoicePhase.LISTENING -> Icons.Default.Mic
                    VoicePhase.THINKING  -> Icons.Outlined.Psychology
                    VoicePhase.SPEAKING  -> Icons.Default.VolumeUp
                    VoicePhase.IDLE      -> Icons.Default.Mic
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
