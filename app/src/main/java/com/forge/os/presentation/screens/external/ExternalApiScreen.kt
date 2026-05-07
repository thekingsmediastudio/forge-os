package com.forge.os.presentation.screens.external

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.external.Capabilities
import com.forge.os.external.ExternalAuditEntry
import com.forge.os.external.ExternalCaller
import com.forge.os.external.GrantStatus
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalApiScreen(onBack: () -> Unit, vm: ExternalApiViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var editing by remember { mutableStateOf<ExternalCaller?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    ModuleScaffold(
        title = "EXTERNAL API",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "Refresh",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showClearConfirm = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "Clear history",
                    tint = ForgeOsPalette.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Master switch ──────────────────────────────────────────────
            item {
                Surface(
                    color = ForgeOsPalette.Surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Allow external apps",
                                color = ForgeOsPalette.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (s.masterEnabled)
                                    "On — granted apps can call the Forge OS API"
                                else
                                    "Off — all external requests are denied",
                                color = ForgeOsPalette.TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = s.masterEnabled,
                            onCheckedChange = vm::setMasterEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ForgeOsPalette.Orange,
                                checkedTrackColor = ForgeOsPalette.Orange.copy(alpha = 0.3f),
                            )
                        )
                    }
                }
            }

            // ── Registered callers ─────────────────────────────────────────
            item {
                Text(
                    "REGISTERED APPS (${s.callers.size})",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
            if (s.callers.isEmpty()) {
                item {
                    Text(
                        "No app has tried to bind yet.",
                        color = ForgeOsPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            } else {
                items(s.callers, key = { it.packageName }) { caller ->
                    CallerCard(
                        caller = caller,
                        onGrant = { editing = caller },
                        onDeny = { vm.deny(caller) },
                        onRevoke = { vm.revoke(caller) },
                        onRemove = { vm.remove(caller) },
                    )
                }
            }

            // ── Call history ───────────────────────────────────────────────
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "CALL HISTORY (${s.recentAudit.size})",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
            if (s.recentAudit.isEmpty()) {
                item {
                    Text(
                        "No external calls yet.",
                        color = ForgeOsPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            } else {
                items(s.recentAudit, key = { "${it.ts}_${it.packageName}_${it.operation}" }) { entry ->
                    AuditEntryCard(entry)
                }
            }
        }
    }

    editing?.let { caller ->
        GrantDialog(
            caller = caller,
            onDismiss = { editing = null },
            onConfirm = { caps -> vm.grant(caller, caps); editing = null },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = ForgeOsPalette.Surface,
            title = {
                Text("Clear history?", color = ForgeOsPalette.Danger,
                    fontFamily = FontFamily.Monospace)
            },
            text = {
                Text(
                    "This deletes all external API call history. Cannot be undone.",
                    color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); showClearConfirm = false }) {
                    Text("CLEAR", color = ForgeOsPalette.Danger, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("CANCEL", color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }
}

// ── Audit entry card ──────────────────────────────────────────────────────────

@Composable
private fun AuditEntryCard(entry: ExternalAuditEntry) {
    var expanded by remember { mutableStateOf(false) }

    val outcomeColor = when (entry.outcome) {
        "ok"           -> Color(0xFF22c55e)
        "error"        -> Color(0xFFef4444)
        "deny"         -> Color(0xFFf59e0b)
        "rate_limited" -> Color(0xFFa78bfa)
        else           -> ForgeOsPalette.TextMuted
    }

    val opLabel = when (entry.operation) {
        "invokeTool" -> "⚙ ${entry.target.ifBlank { "tool" }}"
        "askAgent"   -> "🤖 askAgent"
        "getMemory"  -> "📖 getMemory(${entry.target})"
        "putMemory"  -> "✏️ putMemory(${entry.target})"
        "runSkill"   -> "▶ ${entry.target.ifBlank { "skill" }}"
        "listTools"  -> "📋 listTools"
        "deny"       -> "🚫 denied"
        else         -> entry.operation
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(8.dp))
    ) {
        // ── Header row — always visible ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Outcome dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(outcomeColor, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    opLabel,
                    color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(entry.packageName.substringAfterLast('.'))
                        if (entry.durationMs > 0) append("  ${entry.durationMs}ms")
                        if (entry.outputBytes > 0) append("  ${entry.outputBytes}B")
                    },
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            Text(
                formatTs(entry.ts),
                color = ForgeOsPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = ForgeOsPalette.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }

        // ── Expanded detail ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeOsPalette.Bg, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Full package name
                DetailRow("Package", entry.packageName)

                // Outcome + message
                DetailRow(
                    "Outcome",
                    entry.outcome + if (entry.message.isNotBlank()) " — ${entry.message}" else "",
                    valueColor = outcomeColor,
                )

                // Timestamp
                DetailRow("Time", formatTsFull(entry.ts))

                // Input payload
                if (entry.inputPayload.isNotBlank()) {
                    DetailBlock("Request", entry.inputPayload)
                }

                // Output payload
                if (entry.outputPayload.isNotBlank()) {
                    DetailBlock(
                        label = if (entry.outcome == "error") "Error output" else "Response",
                        value = entry.outputPayload,
                        valueColor = if (entry.outcome == "error") Color(0xFFef4444) else ForgeOsPalette.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = ForgeOsPalette.TextPrimary) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailBlock(label: String, value: String, valueColor: Color = ForgeOsPalette.TextPrimary) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeOsPalette.Surface, RoundedCornerShape(4.dp))
                .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                value,
                color = valueColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

// ── Caller card ───────────────────────────────────────────────────────────────

@Composable
private fun CallerCard(
    caller: ExternalCaller,
    onGrant: () -> Unit,
    onDeny: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            caller.displayName,
            color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            caller.packageName,
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            "Cert: ${caller.signingCertSha256.take(24)}…",
            color = ForgeOsPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))

        val statusColor = when (caller.status) {
            GrantStatus.GRANTED -> Color(0xFF22c55e)
            GrantStatus.DENIED, GrantStatus.REVOKED -> Color(0xFFef4444)
            else -> ForgeOsPalette.TextMuted
        }
        Text(
            caller.status.name,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (caller.status) {
                GrantStatus.PENDING, GrantStatus.DENIED, GrantStatus.REVOKED ->
                    Button(
                        onClick = onGrant,
                        colors = ButtonDefaults.buttonColors(containerColor = ForgeOsPalette.Orange),
                    ) { Text("Grant…", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                GrantStatus.GRANTED ->
                    OutlinedButton(onClick = onRevoke) {
                        Text("Revoke", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
            }
            if (caller.status != GrantStatus.GRANTED) {
                OutlinedButton(onClick = onDeny) {
                    Text("Deny", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onRemove) {
                Text("Forget", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

// ── Grant dialog ──────────────────────────────────────────────────────────────

@Composable
private fun GrantDialog(
    caller: ExternalCaller,
    onDismiss: () -> Unit,
    onConfirm: (Capabilities) -> Unit,
) {
    var listTools    by remember { mutableStateOf(true) }
    var invokeTools  by remember { mutableStateOf(true) }
    var allowAll     by remember { mutableStateOf(false) }
    var toolList     by remember { mutableStateOf("read_file,write_file,python_run") }
    var askAgent     by remember { mutableStateOf(false) }
    var readMemory   by remember { mutableStateOf(false) }
    var writeMemory  by remember { mutableStateOf(false) }
    var runSkills    by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = {
            Text(
                "Grant: ${caller.displayName}",
                color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Choose what this app may do.",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Toggle("List tools",        listTools)   { listTools = it }
                Toggle("Invoke tools",      invokeTools) { invokeTools = it }
                if (invokeTools) {
                    Toggle("Allow ALL tools", allowAll) { allowAll = it }
                    if (!allowAll) {
                        OutlinedTextField(
                            value = toolList,
                            onValueChange = { toolList = it },
                            label = {
                                Text("Allowed tools (comma-separated)",
                                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = ForgeOsPalette.TextPrimary,
                            ),
                        )
                    }
                }
                Toggle("Ask the agent",     askAgent)    { askAgent = it }
                Toggle("Read memory",       readMemory)  { readMemory = it }
                Toggle("Write memory",      writeMemory) { writeMemory = it }
                Toggle("Run skills",        runSkills)   { runSkills = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rate limit: ${caller.rateLimit.callsPerMinute} calls/min, " +
                        "${caller.rateLimit.tokensPerDay} tokens/day",
                    color = ForgeOsPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tools = if (allowAll) listOf("*")
                    else toolList.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                onConfirm(Capabilities(
                    listTools    = listTools,
                    invokeTools  = invokeTools,
                    toolAllowlist = tools,
                    askAgent     = askAgent,
                    readMemory   = readMemory,
                    writeMemory  = writeMemory,
                    runSkills    = runSkills,
                    skillAllowlist = if (runSkills) listOf("*") else emptyList(),
                ))
            }) {
                Text("GRANT", color = ForgeOsPalette.Success, fontFamily = FontFamily.Monospace)
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
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = value,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = ForgeOsPalette.Orange),
        )
        Text(label, color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatTsFull(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
