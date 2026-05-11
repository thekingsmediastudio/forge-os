package com.forge.os.presentation.screens.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.alarms.AlarmAction
import com.forge.os.domain.alarms.AlarmItem
import com.forge.os.domain.alarms.AlarmSession
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModelPickerDialog
import com.forge.os.presentation.screens.common.ModelPickerRow
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(onBack: () -> Unit, viewModel: AlarmsViewModel = hiltViewModel()) {
    val alarms by viewModel.alarms.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    ModuleScaffold(title = "ALARMS", onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // Phase R — Sessions tab so users can verify alarms actually fired.
            TabRow(selectedTabIndex = tab, containerColor = ForgeOsPalette.Surface) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("ALARMS (${alarms.size})", fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
                Tab(selected = tab == 1, onClick = { tab = 1; viewModel.refresh() },
                    text = { Text("SESSIONS (${sessions.size})", fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
            }
            Spacer(Modifier.height(8.dp))

            if (tab == 0) {
                Button(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeOsPalette.Orange),
                ) { Text("+ NEW ALARM", fontFamily = FontFamily.Monospace) }

                Spacer(Modifier.height(12.dp))

                if (alarms.isEmpty()) {
                    Text(
                        "No alarms yet. Tap NEW ALARM to schedule one.",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(alarms, key = { it.id }) { a -> AlarmRow(a, viewModel) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent firings (most recent first):",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.clearSessions() }) {
                        Text("CLEAR", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = ForgeOsPalette.Orange)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (sessions.isEmpty()) {
                    Text(
                        "No sessions logged yet. Sessions appear here after an alarm actually fires.",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.id }) { s -> SessionRow(s) }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddAlarmDialog(
            onDismiss = { showAdd = false },
            onConfirm = { label, ms, action, payload, repeat, provider, model ->
                viewModel.addAlarm(label, ms, action, payload, repeat, provider, model)
                showAdd = false
            },
        )
    }
}

@Composable
private fun SessionRow(s: AlarmSession) {
    val fmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.success) "✓" else "✗",
                color = if (s.success) ForgeOsPalette.Orange else androidx.compose.ui.graphics.Color(0xFFef4444),
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(s.label, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            Text("${s.durationMs}ms", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text("${fmt.format(Date(s.firedAtMs))}  ·  ${s.action}",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        if (s.output.isNotBlank()) {
            Text(s.output.take(400), color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        s.error?.let {
            Text("error: $it", color = androidx.compose.ui.graphics.Color(0xFFef4444),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AlarmRow(a: AlarmItem, vm: AlarmsViewModel) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (a.enabled) "⏰" else "○", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(a.label, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            Switch(checked = a.enabled, onCheckedChange = { vm.toggle(a) })
        }
        Text("${fmt.format(Date(a.triggerAt))}  —  ${a.action}",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (a.payload.isNotBlank()) {
            Text(a.payload.take(120), color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (a.repeatIntervalMs > 0) {
            Text("repeats every ${a.repeatIntervalMs / 60_000}min",
                color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (!a.overrideProvider.isNullOrBlank()) {
            Text("model: ${a.overrideProvider}/${a.overrideModel}",
                color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        TextButton(onClick = { vm.remove(a.id) }) {
            Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFef4444),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, triggerAt: Long, action: AlarmAction, payload: String, repeatMs: Long, provider: String?, model: String?) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var minutesFromNow by remember { mutableStateOf("5") }
    var repeatMin by remember { mutableStateOf("0") }
    var action by remember { mutableStateOf(AlarmAction.NOTIFY) }
    var payload by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    
    var overrideProvider by remember { mutableStateOf<String?>(null) }
    var overrideModel by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Alarm", fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                OutlinedTextField(label, { label = it },
                    label = { Text("Label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(minutesFromNow, { minutesFromNow = it },
                    label = { Text("Minutes from now") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(repeatMin, { repeatMin = it },
                    label = { Text("Repeat every N minutes (0 = one-shot)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                    OutlinedTextField(action.name, {}, readOnly = true,
                        label = { Text("Action") },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        AlarmAction.entries.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.name, fontFamily = FontFamily.Monospace) },
                                onClick = { action = a; expanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(payload, { payload = it },
                    label = { Text("Payload (tool name, python script, or prompt)") },
                    singleLine = false, maxLines = 4,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Trigger at: ${System.currentTimeMillis() + (minutesFromNow.toLongOrNull() ?: 5) * 60_000}",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )

                if (action == AlarmAction.PROMPT_AGENT || action == AlarmAction.RUN_TOOL) {
                    Spacer(Modifier.height(8.dp))
                    ModelPickerRow(
                        override = if (overrideProvider != null && overrideModel != null) overrideProvider!! to overrideModel!! else null,
                        labelPrefix = "MODEL OVERRIDE",
                        defaultLabel = "(use global default route)",
                        onClick = { showPicker = true },
                        onClear = { overrideProvider = null; overrideModel = null }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mins = minutesFromNow.toLongOrNull() ?: 5
                val repeat = repeatMin.toLongOrNull() ?: 0
                onConfirm(
                    label,
                    System.currentTimeMillis() + mins * 60_000,
                    action,
                    payload,
                    repeat * 60_000,
                    overrideProvider,
                    overrideModel
                )
            }) { Text("Add", color = ForgeOsPalette.Orange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ForgeOsPalette.TextMuted) }
        },
        containerColor = ForgeOsPalette.Surface,
    )

    if (showPicker) {
        val viewModel: AlarmsViewModel = hiltViewModel()
        ModelPickerDialog(
            title = "Pick model for alarm",
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
