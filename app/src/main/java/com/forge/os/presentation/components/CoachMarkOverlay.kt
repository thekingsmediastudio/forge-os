package com.forge.os.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/**
 * Data class representing a single coach mark step with spotlight target.
 */
data class CoachMarkStep(
    val title: String,
    val description: String,
    val targetKey: String? = null, // Key to identify the target element
    val tooltipPosition: TooltipPosition = TooltipPosition.BOTTOM
)

enum class TooltipPosition {
    TOP, BOTTOM, AUTO
}

/**
 * Global registry for spotlight targets.
 * Elements register their bounds here for the tutorial to find them.
 */
object SpotlightRegistry {
    private val _targets = mutableStateMapOf<String, Rect>()
    val targets: Map<String, Rect> get() = _targets

    fun register(key: String, rect: Rect) {
        _targets[key] = rect
    }

    fun unregister(key: String) {
        _targets.remove(key)
    }

    fun getBounds(key: String): Rect? = _targets[key]
}

/**
 * Modifier to register an element as a spotlight target.
 */
fun Modifier.spotlightTarget(key: String): Modifier = this.onGloballyPositioned { coordinates ->
    SpotlightRegistry.register(key, coordinates.boundsInRoot())
}

/**
 * Coach mark overlay with real spotlight cutout effect.
 * Highlights the target element with a transparent cutout in the dark overlay.
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
    val targetBounds = step.targetKey?.let { SpotlightRegistry.getBounds(it) }

    Box(modifier = modifier.fillMaxSize()) {
        // Dark overlay with spotlight cutout
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { onSkip() }
                }
        ) {
            val path = Path().apply {
                addRect(Rect(0f, 0f, size.width, size.height))
            }

            // Cut out the spotlight area if we have target bounds
            targetBounds?.let { bounds ->
                val padding = 16.dp.toPx()
                val spotlightRect = Rect(
                    left = bounds.left - padding,
                    top = bounds.top - padding,
                    right = bounds.right + padding,
                    bottom = bounds.bottom + padding
                )
                path.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = spotlightRect,
                        radiusX = 16.dp.toPx(),
                        radiusY = 16.dp.toPx()
                    )
                )
            }

            // Draw with even-odd fill to create cutout
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.75f),
                style = Fill
            )
        }

        // Pulsing ring around target
        targetBounds?.let { bounds ->
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val ringScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "ringScale"
            )
            val ringAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "ringAlpha"
            )

            val density = LocalDensity.current
            val padding = with(density) { 16.dp.toPx() }

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (bounds.left - padding).toDp() },
                        y = with(density) { (bounds.top - padding).toDp() }
                    )
                    .size(
                        width = with(density) { (bounds.width + padding * 2).toDp() },
                        height = with(density) { (bounds.height + padding * 2).toDp() }
                    )
            ) {
                // Animated border
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 2.dp,
                        color = forgePalette.orange.copy(alpha = ringAlpha)
                    )
                ) {}
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
                    TooltipPosition.AUTO -> {
                        // Auto-position based on target location
                        targetBounds?.let { bounds ->
                            val screenHeight = LocalDensity.current.run { 
                                androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() 
                            }
                            if (bounds.top < screenHeight / 2) Alignment.BottomCenter else Alignment.TopCenter
                        } ?: Alignment.BottomCenter
                    }
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
