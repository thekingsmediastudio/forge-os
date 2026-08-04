package com.forge.os.presentation.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    viewModel: ProjectsViewModel = hiltViewModel()) {
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
        }) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val active = state.active
                    Row(
                        Modifier.fillMaxWidth()
                            .background(ForgeOsPalette.Surface2, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null,
                            tint = if (active == null) ForgeOsPalette.TextDim else ForgeOsPalette.Orange,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (active != null) "Active: ${active.name}" else "No active project",
                            color = if (active == null) ForgeOsPalette.TextMuted else ForgeOsPalette.Orange,
                            fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (active != null) TextButton(onClick = { viewModel.activate(null) }) {
                            Text("CLEAR", color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
                        }
                    }
                }
                if (state.projects.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📁", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No projects yet",
                            color = ForgeOsPalette.TextPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to create your first scoped workspace",
                            color = ForgeOsPalette.TextMuted, fontSize = 12.sp)
                    }
                }
                items(state.projects, key = { it.slug }) { p ->
                    ProjectCard(
                        project = p,
                        active = state.active?.slug == p.slug,
                        fileCount = viewModel.fileCount(p.slug),
                        onActivate = { viewModel.activate(p) },
                        onClick = { inspecting = p })
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
        onDismiss = { creating = false })
    val ins = inspecting
    if (ins != null) DetailDialog(
        project = ins,
        onSave = { p -> viewModel.update(p); inspecting = null },
        onDelete = { viewModel.delete(ins.slug); inspecting = null },
        onDismiss = { inspecting = null })
}

@Composable
private fun ProjectCard(
    project: Project,
    active: Boolean,
    fileCount: Int,
    onActivate: () -> Unit,
    onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (active) ForgeOsPalette.Orange else ForgeOsPalette.Border,
                RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Folder icon
            Box(
                Modifier.size(36.dp)
                    .background(ForgeOsPalette.Orange.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Text("📁", fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, color = ForgeOsPalette.TextPrimary, fontSize = 14.sp)
                Text("workspace/projects/${project.slug}",
                    color = ForgeOsPalette.TextDim, fontSize = 10.sp)
            }
            if (active) StatusPill("ACTIVE", ForgeOsPalette.Orange, ForgeOsPalette.Surface2)
            else IconButton(onClick = onActivate, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.CheckCircle, "Activate",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (project.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(project.description, color = ForgeOsPalette.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        // Stat chips row
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectStatChip("📄", "$fileCount files")
            ProjectStatChip("🔧", "${project.scopedTools.size} tools")
            if (project.scopedMemoryTags.isNotEmpty()) {
                ProjectStatChip("🏷", "${project.scopedMemoryTags.size} tags")
            }
        }
    }
}

@Composable
private fun ProjectStatChip(icon: String, label: String) {
    Row(
        Modifier.background(ForgeOsPalette.Surface2, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun CreateDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text("New project", color = ForgeOsPalette.Orange, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("name", color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
                OutlinedTextField(value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary, fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Text("description", color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary, fontSize = 12.sp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, desc) }, enabled = name.isNotBlank()) {
                Text("CREATE", color = ForgeOsPalette.Success)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted)
            }
        })
}

@Composable
private fun DetailDialog(
    project: Project,
    onSave: (Project) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit) {
    var description by remember { mutableStateOf(project.description) }
    var toolList by remember { mutableStateOf(project.scopedTools) }
    var newTool by remember { mutableStateOf("") }
    var scopedTags by remember { mutableStateOf(project.scopedMemoryTags.joinToString(",")) }
    var agentId by remember { mutableStateOf(project.scopedAgentId ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ForgeOsPalette.Surface,
            title = { Text("Delete project?", color = ForgeOsPalette.Danger, fontSize = 14.sp) },
            text = {
                Text(
                    "\"${project.name}\" and its workspace folder will be permanently removed. This cannot be undone.",
                    color = ForgeOsPalette.TextMuted, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("DELETE", color = ForgeOsPalette.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("CANCEL", color = ForgeOsPalette.TextMuted)
                }
            })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text(project.name, color = ForgeOsPalette.Orange, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(project.slug, color = ForgeOsPalette.TextDim, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Lab("description", description) { description = it }

                // Scoped tools as chips + add field
                Text("scoped tools", color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                if (toolList.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        toolList.forEach { tool ->
                            Row(
                                Modifier.background(ForgeOsPalette.Surface2, RoundedCornerShape(8.dp))
                                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(tool, color = ForgeOsPalette.TextPrimary, fontSize = 11.sp)
                                Spacer(Modifier.width(2.dp))
                                Text("✕", color = ForgeOsPalette.TextDim, fontSize = 10.sp,
                                    modifier = Modifier.clickable { toolList = toolList - tool }
                                        .padding(horizontal = 4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTool, onValueChange = { newTool = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("add tool…", color = ForgeOsPalette.TextDim, fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = ForgeOsPalette.TextPrimary, fontSize = 12.sp))
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            val t = newTool.trim()
                            if (t.isNotEmpty() && t !in toolList) {
                                toolList = toolList + t
                                newTool = ""
                            }
                        },
                        enabled = newTool.isNotBlank()) {
                        Text("ADD", color = ForgeOsPalette.Orange, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))

                Lab("scoped memory tags (comma-sep)", scopedTags) { scopedTags = it }
                Lab("scoped agent id (optional)", agentId) { agentId = it }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("DELETE", color = ForgeOsPalette.Danger)
                }
                TextButton(onClick = {
                    onSave(project.copy(
                        description = description,
                        scopedTools = toolList,
                        scopedMemoryTags = scopedTags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        scopedAgentId = agentId.ifBlank { null }))
                }) { Text("SAVE", color = ForgeOsPalette.Success) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ForgeOsPalette.TextMuted)
            }
        })
}

@Composable
private fun Lab(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = ForgeOsPalette.TextMuted, fontSize = 10.sp)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(), singleLine = label != "description",
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ForgeOsPalette.TextPrimary, fontSize = 12.sp))
    Spacer(Modifier.height(4.dp))
}
