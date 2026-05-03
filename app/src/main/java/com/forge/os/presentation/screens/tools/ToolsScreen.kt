package com.forge.os.presentation.screens.tools

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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill

@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var testing: ToolRow? by remember { mutableStateOf(null) }
    var inspecting: ToolRow? by remember { mutableStateOf(null) }

    val systemTools = remember(state.tools) { state.tools.filter { !it.isPlugin } }
    val pluginTools = remember(state.tools) { state.tools.filter { it.isPlugin } }

    ModuleScaffold(
        title = "TOOLS",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.toggleAudit() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.History, "Audit log",
                    tint = if (state.showAudit) forgePalette.orange else forgePalette.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.showAudit) AuditPanel(
                entries = state.audit,
                onClear = viewModel::clearAudit,
            )

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── System tools section ──────────────────────────────────────
                item(key = "_header_system") {
                    SectionHeader(
                        label = "SYSTEM TOOLS",
                        count = systemTools.size,
                        tint = forgePalette.textMuted,
                        icon = false,
                    )
                }
                items(systemTools, key = { it.name }) { row ->
                    ToolRowCard(
                        row = row,
                        onToggle = { v -> viewModel.toggle(row.name, v) },
                        onConfirmToggle = { v -> viewModel.setRequiresConfirmation(row.name, v) },
                        onTest = { testing = row },
                        onInspect = { inspecting = row },
                    )
                }

                // ── Plugin tools section ──────────────────────────────────────
                if (pluginTools.isNotEmpty()) {
                    item(key = "_header_plugins") {
                        Spacer(Modifier.height(4.dp))
                        SectionHeader(
                            label = "PLUGIN TOOLS",
                            count = pluginTools.size,
                            tint = forgePalette.info,
                            icon = true,
                        )
                    }
                    items(pluginTools, key = { "plugin_${it.name}" }) { row ->
                        ToolRowCard(
                            row = row,
                            onToggle = { v -> viewModel.toggle(row.name, v) },
                            onConfirmToggle = { v -> viewModel.setRequiresConfirmation(row.name, v) },
                            onTest = { testing = row },
                            onInspect = { inspecting = row },
                        )
                    }
                }
            }
        }
    }

    // ToolInspectorSheet is defined in ToolInspectorSheet.kt (same package)
    inspecting?.let { tool ->
        ToolInspectorSheet(
            toolName = tool.name,
            description = tool.description,
            parametersJson = tool.parametersJson,
            onDismiss = { inspecting = null },
        )
    }

    val active = testing
    if (active != null) {
        TestToolDialog(
            row = active,
            running = state.testRunning == active.name,
            onDismiss = { testing = null; viewModel.dismissTestResult() },
            onRun = { args -> viewModel.runTest(active.name, args) },
            result = state.testResult,
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    tint: androidx.compose.ui.graphics.Color,
    icon: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            "$label  ($count)",
            color = tint,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(tint.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun ToolRowCard(
    row: ToolRow,
    onToggle: (Boolean) -> Unit,
    onConfirmToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onInspect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(forgePalette.surface, RoundedCornerShape(6.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                color = forgePalette.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onInspect() }
                    .padding(vertical = 4.dp)
            )
            Spacer(Modifier.width(6.dp))
            if (row.isPlugin) {
                StatusPill("PLUGIN", forgePalette.info, forgePalette.surface2)
            } else if (row.requiresConfirmation) {
                StatusPill("CONFIRM", forgePalette.orange, forgePalette.surface2)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onTest, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.PlayArrow, "Test",
                    tint = forgePalette.success, modifier = Modifier.size(16.dp),
                )
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = forgePalette.orange,
                    checkedTrackColor = forgePalette.orange.copy(alpha = 0.3f),
                    uncheckedThumbColor = forgePalette.textDim,
                    uncheckedTrackColor = forgePalette.surface2,
                ),
            )
        }
        Text(
            row.description,
            color = forgePalette.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!row.isPlugin) {
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "require confirm",
                    color = forgePalette.textDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = row.requiresConfirmation,
                    onCheckedChange = onConfirmToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = forgePalette.orange,
                        checkedTrackColor = forgePalette.orange.copy(alpha = 0.3f),
                        uncheckedThumbColor = forgePalette.textDim,
                        uncheckedTrackColor = forgePalette.surface2,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AuditPanel(
    entries: List<com.forge.os.domain.security.ToolAuditEntry>,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(forgePalette.surface)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AUDIT LOG (${entries.size})",
                color = forgePalette.orange,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("CLEAR", color = forgePalette.danger,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (entries.isEmpty()) {
            Text(
                "(no tool dispatches recorded yet)",
                color = forgePalette.textDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            entries.take(40).forEach { e ->
                val mark = if (e.success) "✓" else "✗"
                val color = if (e.success) forgePalette.success else forgePalette.danger
                Text(
                    "$mark ${e.toolName} [${e.source}] ${e.durationMs}ms — ${e.outputPreview.take(80)}",
                    color = color,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun TestToolDialog(
    row: ToolRow,
    running: Boolean,
    result: String?,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var args by remember { mutableStateOf("{}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = {
            Text(
                "Test ${row.name}",
                color = forgePalette.orange,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "args (JSON)",
                    color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
                if (result != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(forgePalette.bg, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    ) {
                        Text(
                            result,
                            color = forgePalette.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(args) }, enabled = !running) {
                Text(
                    if (running) "RUNNING…" else "RUN",
                    color = forgePalette.success,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = forgePalette.textMuted, fontFamily = FontFamily.Monospace)
            }
        },
    )
}
