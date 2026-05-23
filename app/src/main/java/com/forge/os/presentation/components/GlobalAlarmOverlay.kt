package com.forge.os.presentation.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.domain.agent.InteractionRequest
import com.forge.os.domain.agent.UserInputBroker
import kotlinx.coroutines.launch

/**
 * Global overlay that listens for Alarm triggers and surfaces them
 * as dedicated alerts, regardless of which screen the user is on.
 */
@Composable
fun GlobalAlarmOverlay(userInputBroker: UserInputBroker) {
    val scope = rememberCoroutineScope()
    var pendingAlarm by remember { mutableStateOf<InteractionRequest?>(null) }

    LaunchedEffect(Unit) {
        userInputBroker.questions.collect { q ->
            // Catch alarm-specific routes
            if (q.routeKey.startsWith("ALARM:")) {
                pendingAlarm = q
            }
        }
    }

    if (pendingAlarm != null) {
        val q = pendingAlarm!!
        AlertDialog(
            onDismissRequest = { /* Require explicit dismiss */ },
            title = {
                Text(
                    "⏰ ALARM TRIGGERED",
                    color = ModernAccent,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Text(
                    q.question,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingAlarm = null
                        scope.launch { userInputBroker.submitResponse(q.routeKey, "DISMISSED") }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModernAccent,
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text("DISMISS", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            containerColor = ModernSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        )
    }
}
