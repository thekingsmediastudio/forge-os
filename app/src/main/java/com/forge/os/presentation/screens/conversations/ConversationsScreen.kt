package com.forge.os.presentation.screens.conversations

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import com.forge.os.data.conversations.StoredConversation
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(
    onBack: () -> Unit,
    onOpened: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var renameTarget: StoredConversation? by remember { mutableStateOf(null) }
    var deleteTarget: StoredConversation? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "CONVERSATIONS",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                viewModel.startNew(); onOpened()
            }) {
                Text("+ NEW", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("${state.items.size} conversation(s) — tap to open",
                color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, modifier = Modifier.padding(12.dp))

            if (state.items.isEmpty()) {
                Text("No conversations yet.", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(state.items, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            isCurrent = conv.id == state.currentId,
                            onOpen = { viewModel.switchTo(conv.id); onOpened() },
                            onRename = { renameTarget = conv },
                            onDelete = { deleteTarget = conv },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }

    renameTarget?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(c.id, title); renameTarget = null
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("CANCEL") } }
        )
    }

    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${c.title}\"?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(c.id); deleteTarget = null }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") } }
        )
    }
}

private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@Composable
private fun ConversationRow(
    conv: StoredConversation,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = if (isCurrent) ForgeOsPalette.Orange else ForgeOsPalette.Border
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onOpen() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(conv.title.ifBlank { conv.id }, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            if (isCurrent) {
                Text("●", color = ForgeOsPalette.Orange, fontSize = 14.sp)
            }
        }
        Text("${conv.messages.size} msgs • ${tsFmt.format(Date(conv.updatedAt))}",
            color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        conv.lastModel?.let {
            Text("model: $it", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RENAME", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clickable { onRename() })
            Text("DELETE", color = ForgeOsPalette.Danger,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clickable { onDelete() })
        }
    }
}
