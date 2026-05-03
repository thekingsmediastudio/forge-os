package com.forge.os.presentation.screens.cron

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.cron.CronJob
import com.forge.os.domain.cron.TaskType
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModelPickerDialog
import com.forge.os.presentation.screens.common.ModelPickerRow
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("MMM d HH:mm", Locale.US)

@Composable
fun CronScreen(
    onBack: () -> Unit,
    onOpenSession: () -> Unit = {},
    viewModel: CronViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var inspecting: CronJob? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "CRON",
        onBack = onBack,
        actions = {
            TextButton(onClick = onOpenSession) {
                Text("SESSION", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            IconButton(onClick = { showCreate = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New job",
                    tint = forgePalette.orange, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.jobs.isEmpty()) {
                    item {
                        Text("No scheduled jobs.\nTap + to add one.",
                            color = forgePalette.textMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                items(state.jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        running = state.running == job.id,
                        onToggle = { viewModel.toggle(job.id, it) },
                        onRun = { viewModel.runNow(job.id) },
                        onClick = { inspecting = job },
                    )
                }
                if (state.history.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("RECENT HISTORY", color = forgePalette.orange,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            letterSpacing = 1.sp)
                    }
                    items(state.history.take(20), key = { it.startedAt }) { exec ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(if (exec.success) "✓" else "✗",
                                color = if (exec.success) forgePalette.success else forgePalette.danger,
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("${timeFmt.format(Date(exec.startedAt))}  ${exec.jobName}  (${exec.durationMs}ms)",
                                color = forgePalette.textMuted,
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                Snackbar(containerColor = forgePalette.surface2,
                    contentColor = forgePalette.textPrimary) { Text(it.visuals.message) }
            }
        }
    }

    if (showCreate) {
        CreateJobDialog(
            onCreate = { name, type, payload, schedule, provider, model ->
                viewModel.create(name, type, payload, schedule, provider, model)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    val ins = inspecting
    if (ins != null) {
        JobDetailDialog(
            job = ins,
            history = viewModel.historyFor(ins.id),
            onRun = { viewModel.runNow(ins.id) },
            onRemove = { viewModel.remove(ins.id); inspecting = null },
            onDismiss = { inspecting = null },
        )
    }
}

@Composable
private fun JobCard(
    job: CronJob,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(forgePalette.surface, RoundedCornerShape(6.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.name, color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("${job.taskType} • ${job.schedule.pretty()}",
                    color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                if (!job.overrideProvider.isNullOrBlank()) {
                    Text("model: ${job.overrideProvider}/${job.overrideModel}",
                        color = forgePalette.orange,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Text("next: ${timeFmt.format(Date(job.nextRunAt))}",
                    color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            IconButton(onClick = onRun, enabled = !running, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.PlayArrow,
                    if (running) "Running" else "Run now",
                    tint = if (running) forgePalette.textMuted else forgePalette.success,
                    modifier = Modifier.size(16.dp))
            }
            Switch(
                checked = job.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = forgePalette.orange,
                    checkedTrackColor = forgePalette.orange.copy(alpha = 0.3f),
                    uncheckedThumbColor = forgePalette.textMuted,
                    uncheckedTrackColor = forgePalette.surface2,
                ),
            )
        }
        if (job.runCount > 0 || job.failureCount > 0) {
            Spacer(Modifier.height(4.dp))
            Row {
                StatusPill("runs ${job.runCount}", forgePalette.success, forgePalette.surface2)
                if (job.failureCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    StatusPill("fail ${job.failureCount}", forgePalette.danger, Color(0x22EF4444))
                }
            }
        }
    }
}

@Composable
private fun JobDetailDialog(
    job: CronJob,
    history: List<com.forge.os.domain.cron.CronExecution>,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = {
            Text(job.name, color = forgePalette.orange,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("${job.id} • ${job.taskType}", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                if (!job.overrideProvider.isNullOrBlank()) {
                    Text("Model Override: ${job.overrideProvider}/${job.overrideModel}",
                        color = forgePalette.orange,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Schedule: ${job.schedule.pretty()}", color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("Next run: ${timeFmt.format(Date(job.nextRunAt))}", color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text("PAYLOAD", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                Box(Modifier.fillMaxWidth().background(forgePalette.bg, RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Text(job.payload.take(800), color = forgePalette.textPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("HISTORY (${history.size})", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                history.take(10).forEach { exec ->
                    Text(
                        "${if (exec.success) "✓" else "✗"} ${timeFmt.format(Date(exec.startedAt))} (${exec.durationMs}ms) ${exec.error ?: ""}",
                        color = if (exec.success) forgePalette.success else forgePalette.danger,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onRun) {
                    Text("RUN NOW", color = forgePalette.success,
                        fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = onRemove) {
                    Text("DELETE", color = forgePalette.danger,
                        fontFamily = FontFamily.Monospace)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun CreateJobDialog(
    onCreate: (String, TaskType, String, String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var taskType by remember { mutableStateOf(TaskType.PYTHON) }
    var payload by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 0 * * *") }
    
    var overrideProvider by remember { mutableStateOf<String?>(null) }
    var overrideModel by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = {
            Text("Schedule new job", color = forgePalette.orange,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                LabeledField("name", name) { name = it }
                Spacer(Modifier.height(6.dp))
                Text("type", color = forgePalette.textMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Row {
                    TaskType.values().forEach { t ->
                        TextButton(onClick = { taskType = t }) {
                            Text(t.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = if (taskType == t) forgePalette.orange
                                        else forgePalette.textMuted)
                        }
                    }
                }
                LabeledField("schedule", schedule) { schedule = it }
                
                if (taskType == TaskType.PROMPT) {
                    Spacer(Modifier.height(8.dp))
                    ModelPickerRow(
                        override = if (overrideProvider != null && overrideModel != null) overrideProvider!! to overrideModel!! else null,
                        labelPrefix = "MODEL OVERRIDE",
                        defaultLabel = "(use global default route)",
                        onClick = { showPicker = true },
                        onClear = { overrideProvider = null; overrideModel = null }
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text("payload (${taskType.name.lowercase()})",
                    color = forgePalette.textMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = payload, onValueChange = { payload = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, taskType, payload, schedule, overrideProvider, overrideModel) },
                enabled = name.isNotBlank() && payload.isNotBlank(),
            ) {
                Text("CREATE", color = forgePalette.success,
                    fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )

    if (showPicker) {
        val viewModel: CronViewModel = hiltViewModel()
        ModelPickerDialog(
            title = "Pick model for job",
            availableModels = { viewModel.state.value.availableModels },
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

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = forgePalette.textMuted, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = forgePalette.textPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        ),
    )
}
