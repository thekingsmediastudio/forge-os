package com.forge.os.presentation.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.channels.ChannelConfig
import com.forge.os.domain.channels.IncomingMessage
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    onOpenSessions: () -> Unit = {},
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var composeFor by remember { mutableStateOf<ChannelConfig?>(null) }
    var voiceFor by remember { mutableStateOf<ChannelConfig?>(null) }

    ModuleScaffold(title = "CHANNELS", onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showAdd = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeOsPalette.Orange),
                ) { Text("+ ADD TELEGRAM BOT", fontFamily = FontFamily.Monospace) }
                OutlinedButton(
                    onClick = onOpenSessions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("📜 SESSIONS (${sessions.size})",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("CHANNELS", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            if (channels.isEmpty()) {
                Text("No channels configured yet.",
                    color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    channels.forEach { c ->
                        ChannelRow(c,
                            onToggle = { viewModel.toggle(c) },
                            onDelete = { viewModel.remove(c.id) },
                            onCompose = { composeFor = c },
                            onSendVoice = { voiceFor = c },
                            onAutoReply = { viewModel.setAutoReply(c, it) },
                            onParseMode = { viewModel.setParseMode(c, it) },
                            onAllowList = { viewModel.setAllowedChatIds(c, it) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("RECENT INCOMING (${recent.size})", color = ForgeOsPalette.Orange,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            if (recent.isEmpty()) {
                Text("Nothing yet. Incoming Telegram messages will appear here.",
                    color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp)
            } else {
                recent.take(30).forEach { m -> MessageRow(m) }
            }
        }
    }

    if (showAdd) {
        AddTelegramDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, token, chat, autoReply, parseMode, allow, purpose ->
                viewModel.addTelegram(name, token, chat, autoReply, parseMode, allow, purpose)
                showAdd = false
            }
        )
    }

    composeFor?.let { ch ->
        ComposeDialog(
            channel = ch,
            onDismiss = { composeFor = null },
            onSend = { to, text ->
                viewModel.sendTo(ch.id, to, text); composeFor = null
            }
        )
    }

    voiceFor?.let { ch ->
        VoiceDialog(
            channel = ch,
            onDismiss = { voiceFor = null },
            onSend = { to, path, caption ->
                viewModel.sendVoice(ch.id, to, path, caption); voiceFor = null
            }
        )
    }
}

@Composable
private fun ChannelRow(
    c: ChannelConfig,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onCompose: () -> Unit,
    onSendVoice: () -> Unit,
    onAutoReply: (Boolean) -> Unit,
    onParseMode: (String) -> Unit,
    onAllowList: (String) -> Unit,
) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    var allowEdit by remember(c.id) { mutableStateOf(c.allowedChatIds) }

    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📡", fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(c.displayName, color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("${c.type}  ·  ${c.purpose}  ·  auto-reply: ${if (c.autoReply) "ON" else "OFF"}  ·  ${c.parseMode.ifBlank { "plain" }}",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Switch(checked = c.enabled, onCheckedChange = { onToggle() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCompose) {
                Text("SEND", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            TextButton(onClick = onSendVoice) {
                Text("🎙 VOICE", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "▴ HIDE" else "▾ SETTINGS",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("DELETE", color = ForgeOsPalette.Danger,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        if (expanded) {
            Divider(Modifier.padding(vertical = 6.dp), color = ForgeOsPalette.Border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-reply:", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Switch(checked = c.autoReply, onCheckedChange = onAutoReply)
            }
            Spacer(Modifier.height(4.dp))
            Text("Parse mode:", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("HTML", "MarkdownV2", "Markdown", "").forEach { mode ->
                    val label = if (mode.isBlank()) "plain" else mode
                    val selected = c.parseMode == mode
                    OutlinedButton(
                        onClick = { onParseMode(mode) },
                        colors = if (selected)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = ForgeOsPalette.Orange.copy(alpha = 0.2f))
                        else ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = allowEdit, onValueChange = { allowEdit = it },
                label = { Text("Allowed chat ids (comma-separated, blank = all)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAllowList(allowEdit) }) {
                    Text("SAVE ALLOW-LIST", color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: IncomingMessage) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface2, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Text("${fmt.format(Date(m.receivedAt))}  ${m.channelType}:${m.fromName}",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text(m.text, color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (m.attachmentKind != null) {
            Text("📎 ${m.attachmentKind} → ${m.attachmentPath ?: "(download failed)"}",
                color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTelegramDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, token: String, chat: String,
                autoReply: Boolean, parseMode: String, allow: String,
                purpose: String) -> Unit,
) {
    var name by remember { mutableStateOf("Telegram Bot") }
    var token by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf("") }
    var autoReply by remember { mutableStateOf(true) }
    var parseMode by remember { mutableStateOf("HTML") }
    var allow by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("personal") }

    val purposes = listOf(
        "personal"  to "👤 Personal",
        "teaching"  to "📚 Teaching",
        "work"      to "💼 Work",
        "support"   to "🎧 Support",
        "custom"    to "⚙️ Custom",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Telegram Bot", fontFamily = FontFamily.Monospace) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(token, { token = it },
                    label = { Text("Bot token (from @BotFather)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(chat, { chat = it },
                    label = { Text("Default chat id (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(allow, { allow = it },
                    label = { Text("Allowed chat ids (CSV, blank = all)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Channel purpose:", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = ForgeOsPalette.TextMuted)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    purposes.forEach { (key, label) ->
                        val selected = purpose == key
                        OutlinedButton(
                            onClick = { purpose = key },
                            colors = if (selected)
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = ForgeOsPalette.Orange.copy(alpha = 0.2f))
                            else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-reply", modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Switch(checked = autoReply, onCheckedChange = { autoReply = it })
                }
                Spacer(Modifier.height(4.dp))
                Text("Parse mode:", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("HTML", "MarkdownV2", "Markdown", "").forEach { mode ->
                        val label = if (mode.isBlank()) "plain" else mode
                        val selected = parseMode == mode
                        OutlinedButton(
                            onClick = { parseMode = mode },
                            colors = if (selected)
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = ForgeOsPalette.Orange.copy(alpha = 0.2f))
                            else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name, token.trim(), chat.trim(), autoReply, parseMode, allow.trim(), purpose)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ComposeDialog(
    channel: ChannelConfig,
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send via ${channel.displayName}", fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                OutlinedTextField(to, { to = it },
                    label = { Text("Chat id") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(body, { body = it },
                    label = { Text("Message (uses ${channel.parseMode.ifBlank { "plain" }} mode)") },
                    modifier = Modifier.fillMaxWidth().height(160.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(to.trim(), body) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun VoiceDialog(
    channel: ChannelConfig,
    onDismiss: () -> Unit,
    onSend: (to: String, path: String, caption: String?) -> Unit,
) {
    var to by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send voice via ${channel.displayName}", fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                OutlinedTextField(to, { to = it },
                    label = { Text("Chat id") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(path, { path = it },
                    label = { Text("Audio file (workspace path or absolute)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(caption, { caption = it },
                    label = { Text("Caption (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Tip: OGG/Opus is best. Other formats may be re-encoded by Telegram.",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = ForgeOsPalette.TextMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSend(to.trim(), path.trim(), caption.takeIf { it.isNotBlank() })
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
