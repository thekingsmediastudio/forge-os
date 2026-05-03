package com.forge.os.presentation.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.forge.os.presentation.theme.LocalForgePalette

/**
 * Phase S — the "padlock you can't open" UI.
 *
 * Lets the user view and edit the per-tool blocked-pattern / blocked-host /
 * blocked-extension lists that PermissionManager enforces. Also lets the
 * user lock these so the agent's `control_set` can't silently widen them.
 */
@Composable
fun AdvancedOverridesScreen(
    onBack: () -> Unit,
    viewModel: AdvancedOverridesViewModel = hiltViewModel(),
) {
    val palette = LocalForgePalette.current
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().background(palette.bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = palette.textMuted, modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "🔒  ADVANCED OVERRIDES", color = palette.orange, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Edit the per-category block lists the agent's PermissionManager enforces. " +
                "These are the policies you couldn't reach from the Tools switches.",
            color = palette.textMuted, fontSize = 11.sp, lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                AgentLockCard(
                    locked = state.lockAgentOut,
                    onChange = viewModel::setLockAgentOut,
                )
            }
            item {
                ListEditorCard(
                    title = "BLOCKED HOSTS (network)",
                    placeholder = "host (e.g. localhost, 127.0.0.1)",
                    items = state.blockedHosts,
                    onAdd = viewModel::addHost,
                    onRemove = viewModel::removeHost,
                    onReset = viewModel::resetHosts,
                )
            }
            item {
                ListEditorCard(
                    title = "BLOCKED EXTENSIONS (file write/download)",
                    placeholder = "ext without dot (e.g. apk)",
                    items = state.blockedExtensions,
                    onAdd = viewModel::addExtension,
                    onRemove = viewModel::removeExtension,
                    onReset = viewModel::resetExtensions,
                )
            }
            item {
                ListEditorCard(
                    title = "BLOCKED CONFIG PATHS",
                    placeholder = "config path prefix (e.g. sandboxLimits.blockedPaths)",
                    items = state.blockedConfigPaths,
                    onAdd = viewModel::addConfigPath,
                    onRemove = viewModel::removeConfigPath,
                    onReset = viewModel::resetConfigPaths,
                )
            }
        }
    }
}

@Composable
private fun AgentLockCard(locked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalForgePalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lock agent out of these overrides",
                    color = palette.textPrimary, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    if (locked) "ON — control_set cannot modify policy.* paths"
                    else        "OFF — agent's control_set may widen these lists",
                    color = if (locked) Color(0xFF4ade80) else palette.textMuted,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
            Switch(
                checked = locked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.orange,
                    uncheckedThumbColor = Color(0xFF737373),
                    uncheckedTrackColor = Color(0xFF333333),
                )
            )
        }
    }
}

@Composable
private fun ListEditorCard(
    title: String,
    placeholder: String,
    items: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    val palette = LocalForgePalette.current
    var input by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = palette.textMuted, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "reset",
                    color = palette.textMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(4.dp)
                        .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF1f1f1f))
            Spacer(Modifier.height(6.dp))
            for (entry in items) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry, color = palette.textPrimary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = { onRemove(entry) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Remove", tint = palette.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(placeholder, fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Button(
                    onClick = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.orange),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ADD", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("RESET", color = palette.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}
