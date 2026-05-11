package com.forge.os.presentation.screens.projects

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
import androidx.compose.material.icons.filled.CheckCircle
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
import com.forge.os.domain.projects.Project
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill

@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var inspecting: Project? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "PROJECTS",
        onBack = onBack,
        actions = {
            IconButton(onClick = { creating = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New",
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
                item {
                    val active = state.active
                    Row(
                        Modifier.fillMaxWidth()
                            .background(ForgeOsPalette.Surface2, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ACTIVE: ${active?.name ?: "(none)"}",
                            color = if (active == null) ForgeOsPalette.TextMuted else ForgeOsPalette.Orange,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        if (active != null) TextButton(onClick = { viewModel.activate(null) }) {
                            Text("CLEAR", color = ForgeOsPalette.TextMuted,
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
                if (state.projects.isEmpty()) item {
                    Text("No projects yet.\nTap + to create your first scoped workspace.",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                items(state.projects, key = { it.slug }) { p ->
                    ProjectCard(
                        project = p,
                        active = state.active?.slug == p.slug,
                        fileCount = viewModel.fileCount(p.slug),
                        onActivate = { viewModel.activate(p) },
                        onClick = { inspecting = p },
                    )
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                Snackbar(containerColor = ForgeOsPalette.Surface2,
                    contentColor = ForgeOsPalette.TextPrimary) { Text(it.visuals.message) }
            }
        }
    }

    if (creating) CreateDialog(
        onCreate = { name, desc -> viewModel.create(name, desc); creating = false },
        onDismiss = { creating = false },
    )
    val ins = inspecting
    if (ins != null) DetailDialog(
        project = ins,
        onSave = { p -> viewModel.update(p); inspecting = null },
        onDelete = { viewModel.delete(ins.slug); inspecting = null },
        onDismiss = { inspecting = null },
    )
}

@Composable
private fun ProjectCard(
    project: Project,
    active: Boolean,
    fileCount: Int,
    onActivate: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, if (active) ForgeOsPalette.Orange else ForgeOsPalette.Border,
                RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("workspace/projects/${project.slug}",
                    color = ForgeOsPalette.TextDim,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            if (active) StatusPill("ACTIVE", ForgeOsPalette.Orange, ForgeOsPalette.Surface2)
            else IconButton(onClick = onActivate, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.CheckCircle, "Activate",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (project.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(project.description, color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("$fileCount files • ${project.scopedTools.size} tools • ${project.scopedMemoryTags.size} mem tags",
            color = ForgeOsPalette.TextDim,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun CreateDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text("New project", color = ForgeOsPalette.Orange,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("name", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Text("description", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, desc) }, enabled = name.isNotBlank()) {
                Text("CREATE", color = ForgeOsPalette.Success,
                    fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun DetailDialog(
    project: Project,
    onSave: (Project) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var description by remember { mutableStateOf(project.description) }
    var scopedTools by remember { mutableStateOf(project.scopedTools.joinToString(",")) }
    var scopedTags by remember { mutableStateOf(project.scopedMemoryTags.joinToString(",")) }
    var agentId by remember { mutableStateOf(project.scopedAgentId ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text(project.name, color = ForgeOsPalette.Orange,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(project.slug, color = ForgeOsPalette.TextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Lab("description", description) { description = it }
                Lab("scoped tools (comma-sep)", scopedTools) { scopedTools = it }
                Lab("scoped memory tags (comma-sep)", scopedTags) { scopedTags = it }
                Lab("scoped agent id (optional)", agentId) { agentId = it }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("DELETE", color = ForgeOsPalette.Danger,
                        fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = {
                    onSave(project.copy(
                        description = description,
                        scopedTools = scopedTools.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        scopedMemoryTags = scopedTags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        scopedAgentId = agentId.ifBlank { null },
                    ))
                }) { Text("SAVE", color = ForgeOsPalette.Success,
                    fontFamily = FontFamily.Monospace) }
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
private fun Lab(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(), singleLine = label != "description",
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        ),
    )
    Spacer(Modifier.height(4.dp))
}
