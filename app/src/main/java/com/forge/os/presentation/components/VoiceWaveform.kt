package com.forge.os.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.forge.os.presentation.theme.forgePalette
import kotlin.random.Random

/**
 * Animated waveform visualization for voice input.
 * Shows animated bars that react to audio level.
 */
@Composable
fun VoiceWaveform(
    rmsLevel: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 5
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            WaveformBar(
                rmsLevel = rmsLevel,
                isActive = isActive,
                index = index,
                totalBars = barCount
            )
        }
    }
}

@Composable
private fun WaveformBar(
    rmsLevel: Float,
    isActive: Boolean,
    index: Int,
    totalBars: Int
) {
    // Create staggered animation for each bar
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_$index")

    // Base animation for idle state
    val idleHeight by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 600 + (index * 100),
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_height_$index"
    )

    // Stable per-bar randomness — hoisted so it doesn't re-roll on every RMS tick
    val activeMultiplier = remember { 0.5f + Random.nextFloat() * 0.5f }
    val activeHeight = (rmsLevel * activeMultiplier).coerceIn(0.1f, 1f)

    // Smooth the switch between idle and active instead of snapping
    val targetFraction = if (isActive) activeHeight else idleHeight
    val heightFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "bar_height_$index"
    )

    val barColor = if (isActive) forgePalette.orange else forgePalette.textMuted.copy(alpha = 0.5f)

    // Fixed layout height; scale via graphicsLayer to avoid layout passes on every RMS tick
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(48.dp)
            .graphicsLayer {
                scaleY = heightFraction
                // Anchor scaling to the bottom of the bar
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(2.dp))
            .background(barColor)
    )
}

/**
 * Circular waveform that surrounds the voice orb.
 */
@Composable
fun CircularWaveform(
    rmsLevel: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 12
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        repeat(barCount) { index ->
            val angle = (360f / barCount) * index
            val infiniteTransition = rememberInfiniteTransition(label = "circular_$index")
            
            val idleScale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 800 + (index * 50),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "circular_idle_$index"
            )
            
            val activeScale = remember(rmsLevel) {
                1f + (rmsLevel * 0.5f * (0.5f + Random.nextFloat() * 0.5f))
            }
            
            val scale = if (isActive) activeScale else idleScale
            val barColor = if (isActive) 
                forgePalette.orange.copy(alpha = 0.6f) 
            else 
                forgePalette.textMuted.copy(alpha = 0.2f)
            
            Box(
                modifier = Modifier
                    .size(4.dp, (20 * scale).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}
