package com.forge.os.presentation.screens.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.agents.SubAgent
import com.forge.os.domain.agents.SubAgentStatus
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModelPickerDialog
import com.forge.os.presentation.screens.common.ModelPickerRow
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill

@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var spawning by remember { mutableStateOf(false) }
    var inspecting: SubAgent? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "AGENTS",
        onBack = onBack,
        actions = {
            IconButton(onClick = { spawning = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "Spawn",
                    tint = ForgeOsPalette.Orange, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.agents.isEmpty()) item {
                    Text("No sub-agents spawned yet.\nTap + to delegate a goal.",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                items(state.agents, key = { it.id }) { a ->
                    AgentCard(a, onClick = { inspecting = a })
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                Snackbar(containerColor = ForgeOsPalette.Surface2,
                    contentColor = ForgeOsPalette.TextPrimary) { Text(it.visuals.message) }
            }
        }
    }

    if (spawning) SpawnDialog(
        loading = state.spawning,
        onSpawn = { goal, ctx, p, m -> viewModel.spawn(goal, ctx, p, m); spawning = false },
        onDismiss = { spawning = false },
    )
    val ins = inspecting
    if (ins != null) AgentDetailDialog(
        agent = ins,
        transcript = viewModel.transcript(ins.id),
        canCancel = !ins.isTerminal,
        onCancel = { viewModel.cancel(ins.id); inspecting = null },
        onDismiss = { inspecting = null },
    )
}

@Composable
private fun AgentCard(a: SubAgent, onClick: () -> Unit) {
    val (color, bg) = when (a.status) {
        SubAgentStatus.COMPLETED -> ForgeOsPalette.Success to ForgeOsPalette.SuccessBg
        SubAgentStatus.FAILED, SubAgentStatus.TIMED_OUT -> ForgeOsPalette.Danger to ForgeOsPalette.DangerBg
        SubAgentStatus.CANCELLED -> ForgeOsPalette.TextMuted to ForgeOsPalette.Surface2
        SubAgentStatus.RUNNING, SubAgentStatus.SPAWNED -> ForgeOsPalette.Orange to ForgeOsPalette.Surface2
    }
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(a.status.name, color, bg)
            Spacer(Modifier.width(6.dp))
            Text(a.id, color = ForgeOsPalette.TextDim,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            a.durationMs?.let {
                Text("${it}ms", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(a.goal.take(180), color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("d=${a.depth} tools=${a.toolCallCount}",
            color = ForgeOsPalette.TextDim, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
        if (!a.overrideProvider.isNullOrBlank()) {
            Text("model: ${a.overrideProvider}/${a.overrideModel}",
                color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AgentDetailDialog(
    agent: SubAgent,
    transcript: String,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text(agent.id, color = ForgeOsPalette.Orange,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("[${agent.status}] depth=${agent.depth}",
                    color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                if (!agent.overrideProvider.isNullOrBlank()) {
                    Text("Model Override: ${agent.overrideProvider}/${agent.overrideModel}",
                        color = ForgeOsPalette.Orange, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Text("GOAL", color = ForgeOsPalette.Orange, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text(agent.goal, color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                if (transcript.isNotBlank()) {
                    Text("TRANSCRIPT", color = ForgeOsPalette.Orange, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Box(Modifier.fillMaxWidth().background(ForgeOsPalette.Bg, RoundedCornerShape(4.dp)).padding(8.dp)) {
                        Text(transcript.take(2000), color = ForgeOsPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                agent.result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("RESULT", color = ForgeOsPalette.Orange, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(it.take(2000), color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                agent.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("ERROR", color = ForgeOsPalette.Danger, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(it, color = ForgeOsPalette.Danger, fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            if (canCancel) TextButton(onClick = onCancel) {
                Text("CANCEL", color = ForgeOsPalette.Danger, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun SpawnDialog(
    loading: Boolean,
    onSpawn: (String, String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var goal by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var overrideProvider by remember { mutableStateOf<String?>(null) }
    var overrideModel by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text("Spawn sub-agent", color = ForgeOsPalette.Orange,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("goal", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = goal, onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text("context (optional)", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = context, onValueChange = { context = it },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                ModelPickerRow(
                    override = if (overrideProvider != null && overrideModel != null) overrideProvider!! to overrideModel!! else null,
                    labelPrefix = "MODEL OVERRIDE",
                    defaultLabel = "(use global default route)",
                    onClick = { showPicker = true },
                    onClear = { overrideProvider = null; overrideModel = null }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSpawn(goal, context, overrideProvider, overrideModel) },
                enabled = goal.isNotBlank() && !loading) {
                Text(if (loading) "RUNNING…" else "SPAWN",
                    color = ForgeOsPalette.Success, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )

    if (showPicker) {
        val viewModel: AgentsViewModel = hiltViewModel()
        ModelPickerDialog(
            title = "Pick model for sub-agent",
            availableModels = { viewModel.availableModels() },
            initial = if (overrideProvider != null && overrideModel != null) overrideProvider!! to overrideModel!! else null,
            onDismiss = { showPicker = false },
            onSave = { p, m ->
                overrideProvider = p
                overrideModel = m
                showPicker = false
            }
        )
    }
}
