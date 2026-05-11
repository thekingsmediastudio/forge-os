package com.forge.os.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.os.domain.sandbox.DebuggerRelay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebuggerOverlay() {
    val relay = DebuggerRelay.instance ?: return
    val state by relay.state.collectAsState()

    if (!state.isPaused) return

    // We maintain a local copy of the variables so the user can edit them
    var localVars by remember(state.localVariables) {
        mutableStateOf(state.localVariables)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Interactive Agent Debugger",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Python execution paused. Inspect and modify local variables.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(localVars.entries.toList(), key = { it.key }) { (key, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newVal ->
                                localVars = localVars.toMutableMap().apply { put(key, newVal) }
                            },
                            label = { Text(key) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = { relay.resume(localVars) }) {
                        Text("Resume Execution")
                    }
                }
            }
        }
    }
}
