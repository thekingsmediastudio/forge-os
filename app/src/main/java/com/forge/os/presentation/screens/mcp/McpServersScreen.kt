package com.forge.os.presentation.screens.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.forge.os.data.mcp.McpServer
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun McpServersScreen(
    onBack: () -> Unit,
    viewModel: McpServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var confirmRemove: McpServer? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "MCP SERVERS",
        onBack = onBack,
        actions = {
            TextButton(onClick = { viewModel.refreshTools() }, enabled = !state.busy) {
                Text(if (state.busy) "..." else "REFRESH",
                    color = ForgeOsPalette.Orange, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(onClick = { showAdd = true }) {
                Text("+ ADD", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(
                "Forge talks to MCP servers over JSON-RPC HTTP. Add a server URL, hit REFRESH to pull its tools, and the agent can call them as `mcp.<server>.<tool>`.",
                color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text("SERVERS (${state.servers.size})", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            if (state.servers.isEmpty()) {
                Text("No MCP servers configured yet.", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                state.servers.forEach { s ->
                    ServerRow(s,
                        onToggle = { viewModel.toggle(s.id, it) },
                        onRemove = { confirmRemove = s })
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("DISCOVERED TOOLS (${state.tools.size})",
                color = ForgeOsPalette.Orange, fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            if (state.tools.isEmpty()) {
                Text("No tools cached. Tap REFRESH after adding a server.",
                    color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                state.tools.forEach { (server, spec) ->
                    Column(Modifier.fillMaxWidth()
                        .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
                        .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                        .padding(10.dp)) {
                        Text("mcp.${sanitize(server.name)}.${spec.name}",
                            color = ForgeOsPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (spec.description.isNotBlank()) {
                            Text(spec.description, color = ForgeOsPalette.TextMuted,
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            SnackbarHost(snackbar)
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add MCP server") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("RPC URL (https://...)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it },
                        label = { Text("Bearer token (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.add(name, url, token.takeIf { it.isNotBlank() })
                    showAdd = false
                }) { Text("ADD") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("CANCEL") } }
        )
    }

    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${s.name}?") },
            text = { Text("Tools from this server will no longer be callable.") },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(s.id); confirmRemove = null }) {
                    Text("REMOVE")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun ServerRow(s: McpServer, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            Switch(checked = s.enabled, onCheckedChange = onToggle)
        }
        Text(s.url, color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        if (s.authToken != null) {
            Text("auth: bearer ****", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("REMOVE", color = ForgeOsPalette.Danger,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.clickable { onRemove() })
    }
}

private fun sanitize(name: String) =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "server" }
