package com.forge.os.presentation.screens.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.os.presentation.theme.ForgeOSTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen voice interaction activity.
 * Provides a dedicated UI for voice-based agent interaction.
 */
@AndroidEntryPoint
class VoiceModeActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            ForgeOSTheme {
                VoiceModeScreen(
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun VoiceModeScreen(
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Voice Mode",
                style = MaterialTheme.typography.headlineLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Voice interaction interface",
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(onClick = onClose) {
                Text("Close")
            }
        }
    }
}
