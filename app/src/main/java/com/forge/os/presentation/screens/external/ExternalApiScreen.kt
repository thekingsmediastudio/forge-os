package com.forge.os.presentation.screens.external

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.external.Capabilities
import com.forge.os.external.ExternalCaller
import com.forge.os.external.GrantStatus
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalApiScreen(onBack: () -> Unit, vm: ExternalApiViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var editing by remember { mutableStateOf<ExternalCaller?>(null) }

    ModuleScaffold(title = "🌐 External API", onBack = onBack) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {

            // Master switch
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow other apps to use Forge OS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (s.masterEnabled)
                                "On — apps that have been granted access can call the Forge OS API."
                            else
                                "Off — all external bind / intent / provider requests are denied.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.masterEnabled, onCheckedChange = vm::setMasterEnabled)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Apps requesting access (${s.callers.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s.callers.isEmpty()) {
                Text("No app has tried to bind yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                s.callers.forEach { caller ->
                    CallerCard(
                        caller = caller,
                        onGrant = { editing = caller },
                        onDeny = { vm.deny(caller) },
                        onRevoke = { vm.revoke(caller) },
                        onRemove = { vm.remove(caller) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (s.recentAudit.isEmpty()) {
                Text("No external calls yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                s.recentAudit.take(50).forEach { e ->
                    Text(
                        "${formatTs(e.ts)}  ${e.packageName}  ${e.operation}${
                            if (e.target.isNotEmpty()) "(${e.target})" else ""
                        }  → ${e.outcome}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
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
}

@Composable
private fun CallerCard(
    caller: ExternalCaller,
    onGrant: () -> Unit,
    onDeny: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(caller.displayName, style = MaterialTheme.typography.titleSmall)
            Text(caller.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("Cert: ${caller.signingCertSha256.take(23)}…",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            AssistChip(onClick = {}, label = { Text(caller.status.name) }, enabled = false)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (caller.status) {
                    GrantStatus.PENDING, GrantStatus.DENIED, GrantStatus.REVOKED ->
                        Button(onClick = onGrant) { Text("Grant…") }
                    GrantStatus.GRANTED -> Button(onClick = onRevoke) { Text("Revoke") }
                }
                if (caller.status != GrantStatus.GRANTED) {
                    OutlinedButton(onClick = onDeny) { Text("Deny") }
                }
                TextButton(onClick = onRemove) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun GrantDialog(
    caller: ExternalCaller,
    onDismiss: () -> Unit,
    onConfirm: (Capabilities) -> Unit,
) {
    var listTools by remember { mutableStateOf(true) }
    var invokeTools by remember { mutableStateOf(true) }
    var allowAllTools by remember { mutableStateOf(false) }
    var toolList by remember { mutableStateOf("read_file,write_file,run_python") }
    var askAgent by remember { mutableStateOf(false) }
    var readMemory by remember { mutableStateOf(false) }
    var writeMemory by remember { mutableStateOf(false) }
    var runSkills by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grant access to ${caller.displayName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose what this app may do.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Toggle("List tools", listTools) { listTools = it }
                Toggle("Invoke tools", invokeTools) { invokeTools = it }
                if (invokeTools) {
                    Toggle("Allow ALL tools", allowAllTools) { allowAllTools = it }
                    if (!allowAllTools) {
                        OutlinedTextField(
                            value = toolList,
                            onValueChange = { toolList = it },
                            label = { Text("Allowed tools (comma-separated)") },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Toggle("Ask the agent", askAgent) { askAgent = it }
                Toggle("Read memory", readMemory) { readMemory = it }
                Toggle("Write memory", writeMemory) { writeMemory = it }
                Toggle("Run installed skills", runSkills) { runSkills = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Defaults: ${caller.rateLimit.callsPerMinute} calls/min, " +
                        "${caller.rateLimit.tokensPerDay} tokens/day",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tools = if (allowAllTools) listOf("*")
                    else toolList.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                onConfirm(Capabilities(
                    listTools = listTools,
                    invokeTools = invokeTools,
                    toolAllowlist = tools,
                    askAgent = askAgent,
                    readMemory = readMemory,
                    writeMemory = writeMemory,
                    runSkills = runSkills,
                    skillAllowlist = if (runSkills) listOf("*") else emptyList(),
                ))
            }) { Text("Grant") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = onChange)
        Text(label)
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
