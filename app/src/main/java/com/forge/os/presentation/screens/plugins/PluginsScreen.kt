package com.forge.os.presentation.screens.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.plugins.PluginManifest
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill

@Composable
fun PluginsScreen(
    onBack: () -> Unit,
    viewModel: PluginsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showInstaller by remember { mutableStateOf(false) }
    var inspecting: PluginManifest? by remember { mutableStateOf(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.installFromZip(uri) }

    ModuleScaffold(
        title = "PLUGINS",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showInstaller = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "Install",
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
                if (state.plugins.isEmpty()) {
                    item {
                        Text("No plugins installed.\nTap + to install a .zip or paste a manifest.",
                            color = ForgeOsPalette.TextMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                items(state.plugins, key = { it.id }) { p ->
                    PluginCard(p,
                        onToggle = { viewModel.setEnabled(p.id, it) },
                        onClick = { inspecting = p })
                }
            }
            SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter)) {
                Snackbar(containerColor = ForgeOsPalette.Surface2,
                    contentColor = ForgeOsPalette.TextPrimary) { Text(it.visuals.message) }
            }
        }
    }

    if (showInstaller) {
        InstallerDialog(
            installing = state.installing,
            onPickZip = { showInstaller = false; zipPicker.launch("*/*") },
            onPasteInstall = { manifest, code ->
                viewModel.installFromText(manifest, code)
                showInstaller = false
            },
            onDismiss = { showInstaller = false },
        )
    }

    val ins = inspecting
    if (ins != null) {
        DetailDialog(
            plugin = ins,
            onUninstall = {
                viewModel.uninstall(ins.id)
                inspecting = null
            },
            onDismiss = { inspecting = null },
        )
    }
}

@Composable
private fun PluginCard(
    p: PluginManifest,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${p.name} v${p.version}", color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(p.id, color = ForgeOsPalette.TextDim,
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            if (p.source == "builtin") {
                StatusPill("BUILTIN", ForgeOsPalette.Info, ForgeOsPalette.Surface2)
                Spacer(Modifier.width(6.dp))
            }
            Switch(
                checked = p.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ForgeOsPalette.Orange,
                    checkedTrackColor = ForgeOsPalette.Orange.copy(alpha = 0.3f),
                    uncheckedThumbColor = ForgeOsPalette.TextDim,
                    uncheckedTrackColor = ForgeOsPalette.Surface2,
                ),
            )
        }
        Text(p.description, color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(4.dp))
        Row {
            Text("${p.tools.size} tools • ${p.permissions.size} perms",
                color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClick) {
                Text("DETAILS", color = ForgeOsPalette.Orange,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DetailDialog(
    plugin: PluginManifest,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = {
            Text("${plugin.name} v${plugin.version}", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("by ${plugin.author}", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(plugin.description, color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Text("PERMISSIONS", color = ForgeOsPalette.Orange, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                if (plugin.permissions.isEmpty()) {
                    Text("(none)", color = ForgeOsPalette.TextDim, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                } else {
                    plugin.permissions.forEach {
                        Text(" • $it", color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("TOOLS", color = ForgeOsPalette.Orange, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                plugin.tools.forEach { t ->
                    Text(" • ${t.name} — ${t.description}",
                        color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            if (plugin.source != "builtin") {
                TextButton(onClick = onUninstall) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, null,
                            tint = ForgeOsPalette.Danger, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("UNINSTALL", color = ForgeOsPalette.Danger,
                            fontFamily = FontFamily.Monospace)
                    }
                }
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
private fun InstallerDialog(
    installing: Boolean,
    onPickZip: () -> Unit,
    onPasteInstall: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manifest by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = {
            Text("Install plugin", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = onPickZip, enabled = !installing) {
                    Text("📦 Pick .zip from device", color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text("— or paste —", color = ForgeOsPalette.TextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
                Text("manifest.json", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = manifest, onValueChange = { manifest = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text("entrypoint .py", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPasteInstall(manifest, code) },
                enabled = manifest.isNotBlank() && code.isNotBlank() && !installing,
            ) {
                Text(if (installing) "INSTALLING…" else "INSTALL",
                    color = ForgeOsPalette.Success, fontFamily = FontFamily.Monospace)
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
