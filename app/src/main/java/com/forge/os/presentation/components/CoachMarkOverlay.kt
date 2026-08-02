package com.forge.os.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/**
 * Data class representing a single coach mark step.
 */
data class CoachMarkStep(
    val title: String,
    val description: String,
    val targetPosition: Offset = Offset.Zero,
    val targetSize: Dp = 60.dp,
    val tooltipPosition: TooltipPosition = TooltipPosition.BOTTOM
)

enum class TooltipPosition {
    TOP, BOTTOM, CENTER
}

/**
 * Coach mark overlay that highlights UI elements with tooltips.
 * Shows a dark overlay with a spotlight cutout on the target element.
 */
@Composable
fun CoachMarkOverlay(
    steps: List<CoachMarkStep>,
    currentStep: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty() || currentStep >= steps.size) return

    val step = steps[currentStep]
    val isLastStep = currentStep == steps.lastIndex

    Box(modifier = modifier.fillMaxSize()) {
        // Dark overlay with spotlight effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onSkip)
        )

        // Spotlight circle on target
        if (step.targetPosition != Offset.Zero) {
            val density = LocalDensity.current
            val targetSizePx = with(density) { step.targetSize.toPx() }
            
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (step.targetPosition.x - targetSizePx / 2).toDp() },
                        y = with(density) { (step.targetPosition.y - targetSizePx / 2).toDp() }
                    )
                    .size(step.targetSize)
                    .clip(CircleShape)
                    .background(Color.Transparent)
            ) {
                // Pulsing ring around target
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val ringAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "ringAlpha"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            forgePalette.orange.copy(alpha = ringAlpha * 0.3f),
                            CircleShape
                        )
                )
            }
        }

        // Tooltip card
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(
                when (step.tooltipPosition) {
                    TooltipPosition.TOP -> Alignment.TopCenter
                    TooltipPosition.BOTTOM -> Alignment.BottomCenter
                    TooltipPosition.CENTER -> Alignment.Center
                }
            )
        ) {
            CoachMarkTooltip(
                title = step.title,
                description = step.description,
                currentStep = currentStep + 1,
                totalSteps = steps.size,
                isLastStep = isLastStep,
                onNext = onNext,
                onSkip = onSkip,
                onDone = onDone
            )
        }
    }
}

@Composable
private fun CoachMarkTooltip(
    title: String,
    description: String,
    currentStep: Int,
    totalSteps: Int,
    isLastStep: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        color = forgePalette.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Step indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$currentStep of $totalSteps",
                    color = forgePalette.textMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                // Progress dots
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(totalSteps) { index ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (index < currentStep) forgePalette.orange
                                    else forgePalette.borderSoft,
                                    CircleShape
                                )
                        )
                    }
                }
            }

            // Title
            Text(
                text = title,
                color = forgePalette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            // Description
            Text(
                text = description,
                color = forgePalette.textMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(8.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Skip button
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "Skip",
                        color = forgePalette.textMuted,
                        fontSize = 14.sp
                    )
                }

                // Next/Done button
                Button(
                    onClick = if (isLastStep) onDone else onNext,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = forgePalette.orange
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (isLastStep) "Done" else "Next",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Simple tooltip overlay without spotlight - for general hints.
 */
@Composable
fun SimpleTooltipOverlay(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Dark overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onDismiss)
        )

        // Centered tooltip
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            color = forgePalette.surface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    color = forgePalette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = forgePalette.textMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = forgePalette.orange
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Got it", color = Color.White)
                }
            }
        }
    }
}
