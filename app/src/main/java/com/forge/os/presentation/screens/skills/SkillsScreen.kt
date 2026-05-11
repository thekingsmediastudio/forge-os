package com.forge.os.presentation.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
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
import com.forge.os.domain.memory.SkillEntry
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing: SkillEntry? by remember { mutableStateOf(null) }
    var exportTarget: SkillEntry? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportTarget
        if (uri != null && target != null) viewModel.exportSkill(uri, target)
        exportTarget = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.importSkill(uri) }

    ModuleScaffold(
        title = "SKILLS",
        onBack = onBack,
        actions = {
            IconButton(onClick = { importLauncher.launch("application/json") },
                modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Upload, "Import",
                    tint = forgePalette.textMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { creating = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New",
                    tint = forgePalette.orange, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                placeholder = { Text("search skills…", color = forgePalette.textDim,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                ),
            )
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.skills.isEmpty()) item {
                        Text("No skills stored yet.\nSkills are reusable Python snippets the agent (or you) can run.",
                            color = forgePalette.textMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    items(state.skills, key = { it.name }) { s ->
                        SkillCard(
                            s = s,
                            running = state.testingName == s.name,
                            onTest = { viewModel.runTest(s.name) },
                            onClick = { editing = s },
                            onExport = { exportTarget = s; exportLauncher.launch("${s.name}.skill.json") },
                        )
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Snackbar(containerColor = forgePalette.surface2,
                        contentColor = forgePalette.textPrimary) { Text(it.visuals.message) }
                }
            }
        }
    }

    state.testOutput?.let { out ->
        AlertDialog(
            onDismissRequest = { viewModel.clearTestOutput() },
            containerColor = forgePalette.surface,
            title = { Text("Skill output", color = forgePalette.orange,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .background(forgePalette.bg, RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Text(out, color = forgePalette.textPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearTestOutput() }) {
                    Text("OK", color = forgePalette.orange, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }

    if (creating) SkillEditDialog(
        initial = null,
        onSave = { n, d, c, t -> viewModel.upsert(n, d, c, t); creating = false },
        onDelete = null,
        onDismiss = { creating = false },
    )
    val ed = editing
    if (ed != null) SkillEditDialog(
        initial = ed,
        onSave = { n, d, c, t -> viewModel.upsert(n, d, c, t); editing = null },
        onDelete = { viewModel.delete(ed.name); editing = null },
        onDismiss = { editing = null },
    )
}

@Composable
private fun SkillCard(
    s: SkillEntry,
    running: Boolean,
    onTest: () -> Unit,
    onClick: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(forgePalette.surface, RoundedCornerShape(6.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(s.description, color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            IconButton(onClick = onTest, enabled = !running, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.PlayArrow,
                    if (running) "Running" else "Test",
                    tint = if (running) forgePalette.textDim else forgePalette.success,
                    modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onExport, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Download, "Export",
                    tint = forgePalette.textMuted, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("×${s.useCount} • ${s.code.lines().size} lines",
            color = forgePalette.textDim, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SkillEditDialog(
    initial: SkillEntry?,
    onSave: (String, String, String, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(",") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = { Text(if (initial == null) "New skill" else initial.name,
            color = forgePalette.orange, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                F("name", name, initial == null) { name = it }
                F("description", desc, true) { desc = it }
                Text("code", color = forgePalette.textMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(value = code, onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                F("tags", tags, true) { tags = it }
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("DELETE", color = forgePalette.danger,
                        fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    enabled = name.isNotBlank() && desc.isNotBlank() && code.isNotBlank(),
                    onClick = { onSave(name.trim(), desc, code,
                        tags.split(",").map { it.trim() }.filter { it.isNotBlank() }) },
                ) { Text("SAVE", color = forgePalette.success,
                    fontFamily = FontFamily.Monospace) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun F(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Text(label, color = forgePalette.textMuted, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace)
    OutlinedTextField(value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = forgePalette.textPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp))
    Spacer(Modifier.height(6.dp))
}
