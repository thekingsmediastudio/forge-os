package com.forge.os.presentation.screens.debugger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.debug.ReplayTrace
import com.forge.os.domain.debug.TraceStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebuggerScreen(
    onBack: () -> Unit,
    viewModel: DebuggerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snapshot Debugger") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.clearTraces() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Master List
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                if (state.traces.isEmpty()) {
                    Text(
                        text = "No traces recorded yet.",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.traces, key = { it.id }) { trace ->
                            TraceListItem(
                                trace = trace,
                                isSelected = trace.id == state.selectedTrace?.id,
                                onClick = { viewModel.selectTrace(trace.id) }
                            )
                        }
                    }
                }
            }

            // Detail View
            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .padding(start = 1.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                state.selectedTrace?.let { trace ->
                    TraceDetailView(trace = trace)
                } ?: run {
                    Text(
                        text = "Select a trace to view details",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TraceListItem(trace: ReplayTrace, isSelected: Boolean, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = df.format(Date(trace.startedAt))
    
    val statusColor = when {
        trace.completedAt == null -> MaterialTheme.colorScheme.primary
        trace.success -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${trace.steps.size} steps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = trace.prompt,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun TraceDetailView(trace: ReplayTrace) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Agent: ${trace.agentName} | Model: ${trace.model}", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Goal:", style = MaterialTheme.typography.titleMedium)
            Text(trace.prompt, style = MaterialTheme.typography.bodyLarge)
            
            if (trace.completedAt != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val duration = (trace.completedAt - trace.startedAt) / 1000.0
                Text("Result: ${if (trace.success) "Success" else "Failed"} (${duration}s)", 
                    color = if (trace.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        }

        items(trace.steps) { step ->
            TraceStepItem(step = step)
        }
    }
}

@Composable
fun TraceStepItem(step: TraceStep) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Text(
                    text = "Iteration ${step.iteration}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Memory Context:", style = MaterialTheme.typography.labelMedium)
                    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                        Text(step.memoryContext.take(500) + if (step.memoryContext.length > 500) "..." else "", 
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Model Raw Response:", style = MaterialTheme.typography.labelMedium)
                    Text(step.rawResponse, style = MaterialTheme.typography.bodyMedium)

                    if (step.toolCalls.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tools Executed:", style = MaterialTheme.typography.labelMedium)
                        step.toolCalls.forEach { tc ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("⚡ ${tc.name} (${tc.durationMs}ms)", style = MaterialTheme.typography.titleSmall)
                                    Text("Args: ${tc.argsJson}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val resultColor = if (tc.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    Text("Result: ${tc.result?.take(200)}", style = MaterialTheme.typography.bodySmall, color = resultColor)
                                }
                            }
                        }
                    }

                    if (step.nestedTraces.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sub-Agent Traces:", style = MaterialTheme.typography.labelMedium)
                        step.nestedTraces.forEach { nested ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("Agent ID: ${nested.id}", style = MaterialTheme.typography.labelSmall)
                                    TraceDetailView(trace = nested)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
