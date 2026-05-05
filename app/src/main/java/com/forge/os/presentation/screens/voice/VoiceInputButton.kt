package com.forge.os.presentation.screens.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.theme.LocalForgePalette

private val Orange: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalForgePalette.current.orange
private val Surface: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalForgePalette.current.surface
private val TextPrimary: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
private val TextMuted: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalForgePalette.current.textMuted

/**
 * Voice input button with listening animation.
 * Shows a microphone icon that pulses when listening.
 */
@Composable
fun VoiceInputButton(
    onVoiceInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceInputViewModel = hiltViewModel()
) {
    val isListening by viewModel.isListening.collectAsState()
    val lastRecognizedText by viewModel.lastRecognizedText.collectAsState()
    val isAvailable by viewModel.isAvailable.collectAsState()
    
    // Send recognized text to callback
    LaunchedEffect(lastRecognizedText) {
        if (lastRecognizedText.isNotBlank()) {
            onVoiceInput(lastRecognizedText)
        }
    }
    
    if (!isAvailable) {
        // Don't show button if voice input is not available
        return
    }
    
    Box(modifier = modifier) {
        IconButton(
            onClick = {
                if (isListening) {
                    viewModel.stopListening()
                } else {
                    viewModel.startListening()
                }
            },
            modifier = Modifier
                .background(
                    if (isListening) Color(0xFFef4444) else Color(0xFF333333),
                    RoundedCornerShape(8.dp)
                )
                .size(48.dp)
        ) {
            if (isListening) {
                PulsingMicIcon()
            } else {
                Icon(
                    Icons.Default.Mic,
                    "Voice Input",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Pulsing microphone icon animation for when listening.
 */
@Composable
private fun PulsingMicIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )
    
    Icon(
        Icons.Default.Mic,
        "Listening",
        tint = Color.White,
        modifier = Modifier
            .size(20.dp)
            .scale(scale)
    )
}

/**
 * Voice input status indicator.
 * Shows when voice input is active and what was recognized.
 */
@Composable
fun VoiceInputStatus(
    viewModel: VoiceInputViewModel = hiltViewModel()
) {
    val isListening by viewModel.isListening.collectAsState()
    val lastRecognizedText by viewModel.lastRecognizedText.collectAsState()
    
    androidx.compose.animation.AnimatedVisibility(
        visible = isListening,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            color = Color(0xFF1a1a1a),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing indicator
                val infiniteTransition = rememberInfiniteTransition(label = "listening_pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "listening_alpha"
                )
                
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFFef4444).copy(alpha = alpha), CircleShape)
                )
                
                Spacer(Modifier.width(12.dp))
                
                Column {
                    Text(
                        "🎤 Listening...",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (lastRecognizedText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            lastRecognizedText,
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
