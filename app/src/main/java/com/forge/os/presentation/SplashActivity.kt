package com.forge.os.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Phase U — splash screen with the Forge logo. Shown for ~1.4s on cold-start
 * before the main UI loads, then forwards to MainActivity. Set as LAUNCHER in
 * AndroidManifest.xml so this is what the user sees first when they tap the
 * launcher icon.
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

private val Orange = Color(0xFFFF6B35)
private val OrangeDeep = Color(0xFFE55A2B)
private val BgDark = Color(0xFF0E0E10)
private val BgDeep = Color(0xFF1C1C1E)

@Composable
private fun SplashContent(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1_400)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgDark, BgDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ForgeLogoMark()
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Forge OS",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "kindling…",
                color = Color(0xFF9C9CA0),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(34.dp))
            CircularProgressIndicator(
                color = Orange,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ForgeLogoMark() {
    val transition = rememberInfiniteTransition(label = "logoSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(pulse),
        contentAlignment = Alignment.Center,
    ) {
        // Slowly-spinning ember halo behind the disc.
        Box(
            modifier = Modifier
                .size(132.dp)
                .rotate(angle)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Orange.copy(alpha = 0.10f),
                            Orange.copy(alpha = 0.55f),
                            OrangeDeep.copy(alpha = 0.10f),
                            Orange.copy(alpha = 0.10f),
                        )
                    )
                ),
        )
        // Solid disc with the "F" mark.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(OrangeDeep, Orange))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "F",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
    }
}
