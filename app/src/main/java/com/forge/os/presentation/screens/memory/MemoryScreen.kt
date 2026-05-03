package com.forge.os.presentation.screens.memory

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.forge.os.domain.companion.EpisodicMemory
import com.forge.os.domain.memory.FactEntry
import com.forge.os.domain.memory.SkillEntry
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showWipe by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editingFact: FactEntry? by remember { mutableStateOf(null) }
    var creatingFact by remember { mutableStateOf(false) }
    var editingSkill: SkillEntry? by remember { mutableStateOf(null) }
    var creatingSkill by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.importFrom(uri) }

    ModuleScaffold(
        title = "MEMORY",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                if (state.tab == MemoryTab.FACTS) creatingFact = true
                else if (state.tab == MemoryTab.SKILLS) creatingSkill = true
            }, modifier = Modifier.size(32.dp),
                enabled = state.tab == MemoryTab.FACTS || state.tab == MemoryTab.SKILLS) {
                val canCreate = state.tab == MemoryTab.FACTS || state.tab == MemoryTab.SKILLS
                Icon(Icons.Default.Add, "New",
                    tint = if (!canCreate) ForgeOsPalette.TextDim else ForgeOsPalette.Orange,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, "Menu",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(ForgeOsPalette.Surface)) {
                DropdownMenuItem(
                    text = { Text("Export…", color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Download, null,
                        tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(16.dp)) },
                    onClick = { menuOpen = false; exportLauncher.launch("memory_${System.currentTimeMillis()}.json") },
                )
                DropdownMenuItem(
                    text = { Text("Import…", color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Upload, null,
                        tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(16.dp)) },
                    onClick = { menuOpen = false; importLauncher.launch("application/json") },
                )
                DropdownMenuItem(
                    text = { Text("Wipe all", color = ForgeOsPalette.Danger,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.DeleteForever, null,
                        tint = ForgeOsPalette.Danger, modifier = Modifier.size(16.dp)) },
                    onClick = { menuOpen = false; showWipe = true },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TabsRow(state.tab) { viewModel.selectTab(it) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query, onValueChange = viewModel::setQuery,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        val hint = if (state.searchMode == SearchMode.SEMANTIC)
                            "ask in natural language…" else "search…"
                        Text(hint, color = ForgeOsPalette.TextDim,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                )
                if (state.tab == MemoryTab.FACTS) {
                    Spacer(Modifier.size(6.dp))
                    val active = state.searchMode == SearchMode.SEMANTIC
                    TextButton(
                        onClick = { viewModel.toggleSearchMode() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (state.semanticBusy) "…"
                            else if (active) "SEM" else "LEX",
                            color = if (active) ForgeOsPalette.Orange else ForgeOsPalette.TextMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (state.tab) {
                        MemoryTab.DAILY -> items(state.daily.reversed()) { e ->
                            EventLine(e.role, e.content, e.timestamp)
                        }
                        MemoryTab.FACTS -> items(state.facts, key = { it.key }) { f ->
                            FactCard(f, score = state.factScores[f.key],
                                onClick = { editingFact = f })
                        }
                        MemoryTab.SKILLS -> items(state.skills, key = { it.name }) { s ->
                            SkillCard(s, onClick = { editingSkill = s })
                        }
                        MemoryTab.EPISODES -> items(state.episodes, key = { it.id }) { e ->
                            EpisodeCard(e, onDelete = { viewModel.deleteEpisode(e.id) })
                        }
                    }
                    item {
                        if ((state.tab == MemoryTab.DAILY && state.daily.isEmpty()) ||
                            (state.tab == MemoryTab.FACTS && state.facts.isEmpty()) ||
                            (state.tab == MemoryTab.SKILLS && state.skills.isEmpty()) ||
                            (state.tab == MemoryTab.EPISODES && state.episodes.isEmpty())) {
                            Text("(empty)", color = ForgeOsPalette.TextDim,
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Snackbar(containerColor = ForgeOsPalette.Surface2,
                        contentColor = ForgeOsPalette.TextPrimary) { Text(it.visuals.message) }
                }
            }
        }
    }

    if (showWipe) {
        AlertDialog(
            onDismissRequest = { showWipe = false },
            containerColor = ForgeOsPalette.Surface,
            title = { Text("Wipe all memory?", color = ForgeOsPalette.Danger,
                fontFamily = FontFamily.Monospace) },
            text = { Text("This permanently deletes facts, skills, and daily events. " +
                "Export first if you want a backup.",
                color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { showWipe = false; viewModel.wipeAll() }) {
                Text("WIPE", color = ForgeOsPalette.Danger,
                    fontFamily = FontFamily.Monospace) } },
            dismissButton = { TextButton(onClick = { showWipe = false }) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace) } },
        )
    }

    val ef = editingFact
    if (ef != null) FactDialog(
        initial = ef,
        onSave = { k, c, t -> viewModel.upsertFact(k, c, t); editingFact = null },
        onDelete = { viewModel.deleteFact(ef.key); editingFact = null },
        onDismiss = { editingFact = null },
    )
    if (creatingFact) FactDialog(
        initial = null,
        onSave = { k, c, t -> viewModel.upsertFact(k, c, t); creatingFact = false },
        onDelete = null,
        onDismiss = { creatingFact = false },
    )
    val es = editingSkill
    if (es != null) SkillDialog(
        initial = es,
        onSave = { n, d, c, t -> viewModel.upsertSkill(n, d, c, t); editingSkill = null },
        onDelete = { viewModel.deleteSkill(es.name); editingSkill = null },
        onDismiss = { editingSkill = null },
    )
    if (creatingSkill) SkillDialog(
        initial = null,
        onSave = { n, d, c, t -> viewModel.upsertSkill(n, d, c, t); creatingSkill = false },
        onDelete = null,
        onDismiss = { creatingSkill = false },
    )
}

@Composable
private fun TabsRow(selected: MemoryTab, onSelect: (MemoryTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(ForgeOsPalette.Surface).padding(8.dp)) {
        MemoryTab.values().forEach { t ->
            val active = t == selected
            Text(
                t.name,
                color = if (active) ForgeOsPalette.Orange else ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun EventLine(role: String, content: String, ts: Long) {
    val color = when (role) {
        "user" -> ForgeOsPalette.Info
        "assistant" -> ForgeOsPalette.Success
        "tool_call", "tool_result" -> ForgeOsPalette.Orange
        else -> ForgeOsPalette.TextMuted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("[$role]", color = color, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
        Text(content.take(400), color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FactCard(f: FactEntry, score: Float? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(10.dp),
    ) {
        Row {
            Text(f.key, color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (score != null) {
                Text("sim ${"%.2f".format(score)}", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.size(8.dp))
            }
            Text("×${f.accessCount}", color = ForgeOsPalette.TextDim,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text(f.content.take(220), color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        if (f.tags.isNotEmpty()) {
            Text(f.tags.joinToString(" ") { "#$it" },
                color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SkillCard(s: SkillEntry, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(10.dp),
    ) {
        Row {
            Text(s.name, color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("×${s.useCount}", color = ForgeOsPalette.TextDim,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text(s.description, color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        Text("${s.code.lines().size} lines", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EpisodeCard(e: EpisodicMemory, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EPISODE", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(java.text.SimpleDateFormat("MMM dd HH:mm",
                java.util.Locale.US).format(java.util.Date(e.timestamp)),
                color = ForgeOsPalette.TextDim,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) {
                Text("DEL", color = ForgeOsPalette.Danger,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Text(e.summary, color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        if (e.moodTrajectory.isNotBlank()) {
            Text("mood: ${e.moodTrajectory}", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (e.keyTopics.isNotEmpty()) {
            Text("topics: ${e.keyTopics.joinToString(", ")}",
                color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (e.followUps.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text("follow-ups:", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            e.followUps.forEach { f ->
                Text("  • ${f.question}", color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FactDialog(
    initial: FactEntry?,
    onSave: (String, String, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(initial?.key ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(",") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = { Text(if (initial == null) "New fact" else "Edit fact",
            color = ForgeOsPalette.Orange, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                LabeledField("key", key, initial == null) { key = it }
                LabeledArea("content", content) { content = it }
                LabeledField("tags (comma-sep)", tags, true) { tags = it }
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("DELETE", color = ForgeOsPalette.Danger, fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = { onSave(key.trim(), content,
                        tags.split(",").map { it.trim() }.filter { it.isNotBlank() }) },
                    enabled = key.isNotBlank() && content.isNotBlank(),
                ) { Text("SAVE", color = ForgeOsPalette.Success, fontFamily = FontFamily.Monospace) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun SkillDialog(
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
        containerColor = ForgeOsPalette.Surface,
        title = { Text(if (initial == null) "New skill" else "Edit skill",
            color = ForgeOsPalette.Orange, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                LabeledField("name", name, initial == null) { name = it }
                LabeledField("description", desc, true) { desc = it }
                Text("code (Python)", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    ),
                )
                LabeledField("tags", tags, true) { tags = it }
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("DELETE", color = ForgeOsPalette.Danger, fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = { onSave(name.trim(), desc, code,
                        tags.split(",").map { it.trim() }.filter { it.isNotBlank() }) },
                    enabled = name.isNotBlank() && desc.isNotBlank() && code.isNotBlank(),
                ) { Text("SAVE", color = ForgeOsPalette.Success, fontFamily = FontFamily.Monospace) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun LabeledField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    Text(label, color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        ),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LabeledArea(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(140.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        ),
    )
    Spacer(Modifier.height(6.dp))
}
