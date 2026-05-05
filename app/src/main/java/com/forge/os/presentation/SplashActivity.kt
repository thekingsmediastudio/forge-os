package com.forge.os.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.R
import com.forge.os.presentation.components.ModernAccent
import com.forge.os.presentation.components.ModernBg
import kotlinx.coroutines.delay

/**
 * Modern splash screen with animated logo and smooth transitions.
 * Uses actual PNG logo from resources with beautiful animations.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SplashContent { goToMain() } }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

@Composable
private fun SplashContent(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2_000) // Show for 2 seconds
        onTimeout()
    }

    // Animated gradient background
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient_offset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ModernBg,
                        ModernAccent.copy(alpha = 0.15f * gradientOffset),
                        ModernBg
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo with entrance animation
            val logoScale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "logo_scale"
            )
            
            // Subtle rotation for visual interest
            val logoRotation by infiniteTransition.animateFloat(
                initialValue = -2f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logo_rotation"
            )
            
            // Glow effect behind logo
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                // Animated glow
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(1f + gradientOffset * 0.1f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    ModernAccent.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                
                // Actual logo image
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Forge OS Logo",
                    modifier = Modifier
                        .size(140.dp)
                        .scale(logoScale)
                        .rotate(logoRotation)
                )
            }
            
            Spacer(Modifier.height(32.dp))
            
            // App name with fade-in
            val textAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(1000, delayMillis = 300),
                label = "text_alpha"
            )
            
            Text(
                text = "Forge OS",
                color = Color.White.copy(alpha = textAlpha),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = "AI-Powered Development Environment",
                color = Color(0xFF9CA3AF).copy(alpha = textAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )
            
            Spacer(Modifier.height(48.dp))
            
            // Progress indicator
            LinearProgressIndicator(
                modifier = Modifier
                    .width(200.dp)
                    .alpha(textAlpha),
                color = ModernAccent,
                trackColor = ModernAccent.copy(alpha = 0.2f)
            )
        }
    }
}
