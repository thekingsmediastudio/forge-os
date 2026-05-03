package com.forge.os.presentation.screens.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.snapshots.SnapshotInfo
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SnapshotsScreen(
    onBack: () -> Unit,
    viewModel: SnapshotsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var confirmRestore: SnapshotInfo? by remember { mutableStateOf(null) }
    var confirmDelete: SnapshotInfo? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "SNAPSHOTS",
        onBack = onBack,
        actions = {
            TextButton(onClick = { showCreate = true }) {
                Text("+ NEW", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Differential snapshots use Git-backed deduplication. Restoring reverts the entire workspace to that state.",
                color = forgePalette.textMuted, fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, modifier = Modifier.padding(12.dp),
            )
            if (state.items.isEmpty()) {
                Text("No snapshots yet.", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(state.items, key = { it.id }) { snap ->
                        SnapshotRow(
                            snap = snap,
                            onRestore = { confirmRestore = snap },
                            onDelete = { confirmDelete = snap },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }

    if (showCreate) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New snapshot") },
            text = {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.create(label.takeIf { it.isNotBlank() })
                    showCreate = false
                }) { Text("CREATE") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("CANCEL") } }
        )
    }

    confirmRestore?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore ${s.label}?") },
            text = { 
                val type = if (s.isDifferential) "Differential Git-snapshot" else "ZIP archive"
                Text("This will revert the workspace to this state using a $type. Unsaved changes in the current workspace will be lost.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.restore(s.id); confirmRestore = null }) {
                    Text("RESTORE")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("CANCEL") } }
        )
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${s.id}?") },
            text = { Text("Permanently remove this snapshot.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(s.id); confirmDelete = null }) {
                    Text("DELETE")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL") } }
        )
    }
}

private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@Composable
private fun SnapshotRow(snap: SnapshotInfo, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(forgePalette.surface, RoundedCornerShape(6.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(snap.label, color = forgePalette.textPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text("${snap.id.take(12)}${if (snap.id.length > 12) "..." else ""} • ${tsFmt.format(Date(snap.createdAt))}",
            color = forgePalette.textMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (snap.isDifferential) "DIFFERENTIAL" else "${snap.fileCount} files • ${formatBytes(snap.sizeBytes)}",
                color = if (snap.isDifferential) forgePalette.orange else forgePalette.textMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp
            )
            if (snap.isDifferential) {
                Spacer(Modifier.width(8.dp))
                Text("(Git VCS)", color = forgePalette.textMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RESTORE", color = forgePalette.orange,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clickable { onRestore() })
            Text("DELETE", color = forgePalette.danger,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clickable { onDelete() })
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "${b / 1024}KB"
    else -> "${b / 1024 / 1024}MB"
}
