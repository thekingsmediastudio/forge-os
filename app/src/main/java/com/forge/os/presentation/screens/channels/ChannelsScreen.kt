package com.forge.os.presentation.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.channels.ChannelConfig
import com.forge.os.domain.channels.IncomingMessage
import com.forge.os.presentation.components.ForgeScreenScaffold
import java.text.SimpleDateFormat
import java.util.*

private val Accent = Color(0xFFFF4500)
private val Danger = Color(0xFFEF4444)
private val CardBg = Color(0xFF0D0D0D)
private val CardBrd = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    onOpenSessions: () -> Unit = {},
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val channels  by viewModel.channels.collectAsState()
    val recent    by viewModel.recent.collectAsState()
    val sessions  by viewModel.sessions.collectAsState()
    var showAdd   by remember { mutableStateOf(false) }
    var showAddPlatform by remember { mutableStateOf<String?>(null) }
    var composeFor by remember { mutableStateOf<ChannelConfig?>(null) }
    var voiceFor   by remember { mutableStateOf<ChannelConfig?>(null) }

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {

            // ── Header ─────────────────────────────────────────────────
            Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Channels", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${channels.count { it.enabled }} active · ${sessions.size} sessions",
                            color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(onClick = onOpenSessions, color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = RoundedCornerShape(11.dp)
                    ) { Text("SESSIONS", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(40.dp).background(Accent.copy(alpha = 0.15f), RoundedCornerShape(13.dp))
                        .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                        .clickable { showAdd = true }, contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(20.dp)) }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // ── Channels section ──────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CHANNELS", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Accent.copy(alpha = 0.2f)))
                }
                if (channels.isEmpty()) {
                    Surface(color = CardBg, border = BorderStroke(1.dp, CardBrd), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("📡", fontSize = 32.sp)
                            Text("No channels configured", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                            Surface(onClick = { showAdd = true }, color = Accent.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Accent.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)
                            ) { Text("ADD CHANNEL", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                        }
                    }
                } else {
                    channels.forEach { c ->
                        ChannelCard(c, onToggle = { viewModel.toggle(c) }, onDelete = { viewModel.remove(c.id) },
                            onCompose = { composeFor = c }, onSendVoice = { voiceFor = c },
                            onAutoReply = { viewModel.setAutoReply(c, it) },
                            onParseMode = { viewModel.setParseMode(c, it) },
                            onAllowList = { viewModel.setAllowedChatIds(c, it) },
                            onStreaming = { viewModel.setStreamingEnabled(c, it) },
                            onGuestMode = { viewModel.setGuestModeEnabled(c, it) },
                            onBotToBot = { viewModel.setBotToBotEnabled(c, it) },
                            onBusiness = { viewModel.setBusinessAutomationEnabled(c, it) },
                            onRichPolls = { viewModel.setRichPollsEnabled(c, it) },
                            onLearning = { viewModel.setLearningEnabled(c, it) })
                    }
                }

                // ── Incoming messages ─────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT INCOMING (${recent.size})", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Accent.copy(alpha = 0.2f)))
                }
                if (recent.isEmpty()) {
                    Text("No incoming messages yet.", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
                } else {
                    recent.take(20).forEach { m -> MessageRow(m) }
                }
            }
        }
    }

    if (showAdd) PlatformPickerDialog(
        onDismiss = { showAdd = false },
        onPick = { platform -> showAdd = false; showAddPlatform = platform }
    )
    when (showAddPlatform) {
        "telegram" -> AddTelegramDialog(
            onDismiss = { showAddPlatform = null },
            onConfirm = { name, token, chat, allow, purpose, stream, guest, b2b, biz, polls ->
                viewModel.addTelegram(name, token, chat, allow, purpose, "", false, stream, guest, b2b, biz, polls)
                showAddPlatform = null
            }
        )
        "discord" -> AddDiscordDialog(
            onDismiss = { showAddPlatform = null },
            onConfirm = { name, token, guildId, allow, purpose, stream ->
                viewModel.addDiscord(name, token, guildId, allow, purpose, stream)
                showAddPlatform = null
            }
        )
        "slack" -> AddSlackDialog(
            onDismiss = { showAddPlatform = null },
            onConfirm = { name, botToken, appToken, allow, purpose, stream ->
                viewModel.addSlack(name, botToken, appToken, allow, purpose, stream)
                showAddPlatform = null
            }
        )
        "whatsapp" -> AddWhatsAppDialog(
            onDismiss = { showAddPlatform = null },
            onConfirm = { name, accessToken, phoneId, verifyToken, allow, purpose ->
                viewModel.addWhatsApp(name, accessToken, phoneId, verifyToken, allow, purpose)
                showAddPlatform = null
            }
        )
    }
    composeFor?.let { ch ->
        ComposeDialog(channel = ch, onDismiss = { composeFor = null },
            onSend = { to, text -> viewModel.sendTo(ch.id, to, text); composeFor = null })
    }
    voiceFor?.let { ch ->
        VoiceDialog(channel = ch, onDismiss = { voiceFor = null },
            onSend = { to, path, caption -> viewModel.sendVoice(ch.id, to, path, caption); voiceFor = null })
    }
}

@Composable
private fun ChannelCard(c: ChannelConfig, onToggle: () -> Unit, onDelete: () -> Unit,
    onCompose: () -> Unit, onSendVoice: () -> Unit,
    onAutoReply: (Boolean) -> Unit, onParseMode: (String) -> Unit, onAllowList: (String) -> Unit,
    onStreaming: (Boolean) -> Unit, onGuestMode: (Boolean) -> Unit, onBotToBot: (Boolean) -> Unit,
    onBusiness: (Boolean) -> Unit, onRichPolls: (Boolean) -> Unit, onLearning: (Boolean) -> Unit) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    var allowEdit by remember(c.id) { mutableStateOf(c.allowedChatIds) }
    Surface(color = CardBg, border = BorderStroke(1.dp, if (c.enabled) Accent.copy(alpha = 0.2f) else CardBrd), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(if (c.enabled) Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
                    .border(1.dp, if (c.enabled) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center
                ) { Text(platformEmoji(c.type), fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("${c.type} · ${c.purpose} · ${c.parseMode.ifBlank { "plain" }}", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                }
                Switch(checked = c.enabled, onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.3f), uncheckedTrackColor = Color.White.copy(alpha = 0.08f)))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onCompose, color = Accent.copy(alpha = 0.1f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.25f)), shape = RoundedCornerShape(10.dp)
                ) { Text("SEND", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
                Surface(onClick = onSendVoice, color = Color.White.copy(alpha = 0.04f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = RoundedCornerShape(10.dp)
                ) { Text("🎙 VOICE", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
                Surface(onClick = { expanded = !expanded }, color = Color.White.copy(alpha = 0.03f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape = RoundedCornerShape(10.dp)
                ) { Text(if (expanded) "▴ HIDE" else "▾ SETTINGS", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
                Spacer(Modifier.weight(1f))
                Surface(onClick = onDelete, color = Danger.copy(alpha = 0.08f), border = BorderStroke(1.dp, Danger.copy(alpha = 0.2f)), shape = RoundedCornerShape(10.dp)
                ) { Text("DELETE", color = Danger, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp)); Divider(color = Color.White.copy(alpha = 0.05f)); Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-reply", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = c.autoReply, onCheckedChange = onAutoReply,
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.3f), uncheckedTrackColor = Color.White.copy(alpha = 0.08f)))
                }
                Spacer(Modifier.height(12.dp)); Divider(color = Color.White.copy(alpha = 0.05f)); Spacer(Modifier.height(12.dp))
                Text("SOVEREIGNTY FEATURES (API 10.0)", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                
                SovereigntyToggle("Live Streaming", "Progressive word-by-word updates", c.streamingEnabled, onStreaming)
                SovereigntyToggle("Guest Mode", "Reply to messages without joining chat", c.guestModeEnabled, onGuestMode)
                SovereigntyToggle("Agent Autonomy", "Enable bot-to-bot communication", c.botToBotEnabled, onBotToBot)
                SovereigntyToggle("Business Automation", "Manage your personal profile messages", c.businessAutomationEnabled, onBusiness)
                SovereigntyToggle("Enhanced Polls", "Rich media & participation restrictions", c.richPollsEnabled, onRichPolls)

                Spacer(Modifier.height(12.dp)); Divider(color = Color.White.copy(alpha = 0.05f)); Spacer(Modifier.height(12.dp))
                Text("CHANNEL LEARNING", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                SovereigntyToggle(
                    "Learn About User",
                    "Agent extracts name, interests, preferences & style from messages to personalise replies",
                    c.learnFromConversations,
                    onLearning
                )

                Spacer(Modifier.height(12.dp)); Divider(color = Color.White.copy(alpha = 0.05f)); Spacer(Modifier.height(12.dp))
                Text("PARSE MODE", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("HTML", "MarkdownV2", "Markdown", "").forEach { mode ->
                        val lbl = if (mode.isBlank()) "plain" else mode
                        val sel = c.parseMode == mode
                        Surface(onClick = { onParseMode(mode) }, shape = RoundedCornerShape(10.dp),
                            color = if (sel) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
                            border = BorderStroke(1.dp, if (sel) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                        ) { Text(lbl, color = if (sel) Accent else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("ALLOWED CHAT IDS", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(44.dp).background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(value = allowEdit, onValueChange = { allowEdit = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
                        if (allowEdit.isEmpty()) Text("blank = allow all", color = Color.White.copy(alpha = 0.2f), fontSize = 13.sp)
                    }
                    Surface(onClick = { onAllowList(allowEdit) }, color = Accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.3f)), shape = RoundedCornerShape(10.dp)
                    ) { Text("SAVE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: IncomingMessage) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Surface(color = Color.White.copy(alpha = 0.02f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(Accent, CircleShape))
                Text("${fmt.format(Date(m.receivedAt))}  ${m.channelType}:${m.fromName}", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(m.text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            m.attachmentKind?.let { Spacer(Modifier.height(4.dp)); Text("📎 $it → ${m.attachmentPath ?: "(failed)"}", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp) }
        }
    }
}

@Composable
private fun SovereigntyToggle(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTelegramDialog(onDismiss: () -> Unit, onConfirm: (name: String, token: String, chat: String, allow: String, purpose: String, stream: Boolean, guest: Boolean, b2b: Boolean, biz: Boolean, polls: Boolean) -> Unit) {
    var name by remember { mutableStateOf("Telegram Bot") }
    var token by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf("") }
    var autoReply by remember { mutableStateOf(true) }
    var parseMode by remember { mutableStateOf("HTML") }
    var allow by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("personal") }
    
    var stream by remember { mutableStateOf(true) }
    var guest by remember { mutableStateOf(false) }
    var b2b by remember { mutableStateOf(false) }
    var biz by remember { mutableStateOf(false) }
    var polls by remember { mutableStateOf(true) }

    val purposes = listOf("personal" to "👤 Personal", "teaching" to "📚 Teaching", "work" to "💼 Work", "support" to "🎧 Support", "custom" to "⚙️ Custom")

    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF0D0D0D), shape = RoundedCornerShape(24.dp),
        title = { Text("Add Telegram Bot", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("DISPLAY NAME", name) { name = it }
                TgField("BOT TOKEN (from @BotFather)", token) { token = it }
                TgField("DEFAULT CHAT ID (optional)", chat) { chat = it }
                TgField("ALLOWED CHAT IDS (CSV, blank = all)", allow) { allow = it }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PURPOSE", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        purposes.forEach { (key, lbl) ->
                            val sel = purpose == key
                            Surface(onClick = { purpose = key }, shape = RoundedCornerShape(10.dp),
                                color = if (sel) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
                                border = BorderStroke(1.dp, if (sel) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                            ) { Text(lbl, color = if (sel) Accent else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-reply", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = autoReply, onCheckedChange = { autoReply = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.3f), uncheckedTrackColor = Color.White.copy(alpha = 0.08f)))
                }
                Spacer(Modifier.height(8.dp)); Divider(color = Color.White.copy(alpha = 0.05f)); Spacer(Modifier.height(8.dp))
                Text("FEATURES", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                SovereigntyToggle("Live Streaming", "Word-by-word replies", stream) { stream = it }
                SovereigntyToggle("Guest Mode", "Reply to any mention", guest) { guest = it }
                SovereigntyToggle("Agent Autonomy", "Bot-to-bot talk", b2b) { b2b = it }
                SovereigntyToggle("Business Profile", "Handle personal chats", biz) { biz = it }
                SovereigntyToggle("Enhanced Polls", "Rich media support", polls) { polls = it }
            }
        },
        confirmButton = {
            Surface(onClick = { onConfirm(name, token.trim(), chat.trim(), allow.trim(), purpose, stream, guest, b2b, biz, polls) },
                color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
            ) { Text("ADD", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun ComposeDialog(channel: ChannelConfig, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF0D0D0D), shape = RoundedCornerShape(24.dp),
        title = { Text("Send via ${channel.displayName}", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("CHAT ID", to) { to = it }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MESSAGE (${channel.parseMode.ifBlank { "plain" }} mode)", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    Box(Modifier.fillMaxWidth().height(140.dp).background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)
                    ) { BasicTextField(value = body, onValueChange = { body = it }, textStyle = TextStyle(color = Color.White, fontSize = 13.sp, lineHeight = 18.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxSize()) }
                }
            }
        },
        confirmButton = { Surface(onClick = { onSend(to.trim(), body) }, color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
        ) { Text("SEND", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun VoiceDialog(channel: ChannelConfig, onDismiss: () -> Unit, onSend: (to: String, path: String, caption: String?) -> Unit) {
    var to by remember { mutableStateOf("") }; var path by remember { mutableStateOf("") }; var caption by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF0D0D0D), shape = RoundedCornerShape(24.dp),
        title = { Text("Send Voice via ${channel.displayName}", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("CHAT ID", to) { to = it }
                TgField("AUDIO FILE PATH", path) { path = it }
                TgField("CAPTION (optional)", caption) { caption = it }
                Text("Tip: OGG/Opus is best. Other formats may be re-encoded by Telegram.", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
            }
        },
        confirmButton = { Surface(onClick = { onSend(to.trim(), path.trim(), caption.takeIf { it.isNotBlank() }) }, color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
        ) { Text("SEND", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun TgField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
        Box(Modifier.fillMaxWidth().height(46.dp).background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            if (value.isEmpty()) Text(label.lowercase(), color = Color.White.copy(alpha = 0.2f), fontSize = 13.sp)
        }
    }
}

private fun platformEmoji(type: String) = when (type) {
    "telegram"  -> "✈️"
    "discord"   -> "🎮"
    "slack"     -> "💬"
    "whatsapp"  -> "📱"
    else        -> "📡"
}

@Composable
private fun PlatformPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val platforms = listOf(
        "telegram"  to "Telegram Bot",
        "discord"   to "Discord Bot",
        "slack"     to "Slack Bot",
        "whatsapp"  to "WhatsApp Business",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Channel", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a platform:", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                platforms.forEach { (key, label) ->
                    Surface(
                        onClick = { onPick(key) },
                        color = Color.White.copy(alpha = 0.03f),
                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(platformEmoji(key), fontSize = 22.sp)
                            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDiscordDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, botToken: String, guildId: String, allow: String, purpose: String, stream: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("Discord Bot") }
    var token by remember { mutableStateOf("") }
    var guildId by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("personal") }
    var stream by remember { mutableStateOf(true) }
    val purposes = listOf("personal" to "👤 Personal", "work" to "💼 Work", "support" to "🎧 Support", "custom" to "⚙️ Custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Discord Bot", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("DISPLAY NAME", name) { name = it }
                TgField("BOT TOKEN (from Discord Developer Portal)", token) { token = it }
                TgField("GUILD / SERVER ID (optional)", guildId) { guildId = it }
                TgField("ALLOWED CHANNEL IDS (CSV, blank = all)", allow) { allow = it }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PURPOSE", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        purposes.forEach { (key, lbl) ->
                            val sel = purpose == key
                            Surface(onClick = { purpose = key }, shape = RoundedCornerShape(10.dp),
                                color = if (sel) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
                                border = BorderStroke(1.dp, if (sel) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                            ) { Text(lbl, color = if (sel) Accent else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
                        }
                    }
                }
                SovereigntyToggle("Live Streaming", "Word-by-word via message edits", stream) { stream = it }
                Text("Required intents: GUILDS, GUILD_MESSAGES, MESSAGE_CONTENT, DIRECT_MESSAGES", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp)
            }
        },
        confirmButton = {
            Surface(onClick = { onConfirm(name, token.trim(), guildId.trim(), allow.trim(), purpose, stream) },
                color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
            ) { Text("ADD", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSlackDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, botToken: String, appToken: String, allow: String, purpose: String, stream: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("Slack Bot") }
    var botToken by remember { mutableStateOf("") }
    var appToken by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("personal") }
    var stream by remember { mutableStateOf(true) }
    val purposes = listOf("personal" to "👤 Personal", "work" to "💼 Work", "support" to "🎧 Support", "custom" to "⚙️ Custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Slack Bot", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("DISPLAY NAME", name) { name = it }
                TgField("BOT TOKEN (xoxb-...)", botToken) { botToken = it }
                TgField("APP-LEVEL TOKEN (xapp-... for Socket Mode)", appToken) { appToken = it }
                TgField("ALLOWED CHANNEL IDS (CSV, blank = all)", allow) { allow = it }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PURPOSE", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        purposes.forEach { (key, lbl) ->
                            val sel = purpose == key
                            Surface(onClick = { purpose = key }, shape = RoundedCornerShape(10.dp),
                                color = if (sel) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
                                border = BorderStroke(1.dp, if (sel) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                            ) { Text(lbl, color = if (sel) Accent else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
                        }
                    }
                }
                SovereigntyToggle("Live Streaming", "Word-by-word via chat.update", stream) { stream = it }
                Text("Required scopes: chat:write, files:write, reactions:write, users:read, channels:read, channels:history", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp)
                Text("Socket Mode must be enabled in your Slack App settings.", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp)
            }
        },
        confirmButton = {
            Surface(onClick = { onConfirm(name, botToken.trim(), appToken.trim(), allow.trim(), purpose, stream) },
                color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
            ) { Text("ADD", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddWhatsAppDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, accessToken: String, phoneNumberId: String, verifyToken: String, allow: String, purpose: String) -> Unit,
) {
    var name by remember { mutableStateOf("WhatsApp") }
    var accessToken by remember { mutableStateOf("") }
    var phoneNumberId by remember { mutableStateOf("") }
    var verifyToken by remember { mutableStateOf("forge_wa") }
    var allow by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("personal") }
    val purposes = listOf("personal" to "👤 Personal", "work" to "💼 Work", "support" to "🎧 Support", "custom" to "⚙️ Custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add WhatsApp Business", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TgField("DISPLAY NAME", name) { name = it }
                TgField("ACCESS TOKEN (from Meta Developer Console)", accessToken) { accessToken = it }
                TgField("PHONE NUMBER ID (from Meta Console)", phoneNumberId) { phoneNumberId = it }
                TgField("WEBHOOK VERIFY TOKEN (your choice)", verifyToken) { verifyToken = it }
                TgField("ALLOWED PHONE NUMBERS (CSV, blank = all)", allow) { allow = it }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PURPOSE", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        purposes.forEach { (key, lbl) ->
                            val sel = purpose == key
                            Surface(onClick = { purpose = key }, shape = RoundedCornerShape(10.dp),
                                color = if (sel) Accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f),
                                border = BorderStroke(1.dp, if (sel) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                            ) { Text(lbl, color = if (sel) Accent else Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
                        }
                    }
                }
                Surface(color = Color(0xFF1A1000), border = BorderStroke(1.dp, Color(0xFFFF8C00).copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚠️ SETUP REQUIRED", color = Color(0xFFFF8C00), fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text("1. Create a Meta Developer app with WhatsApp product.", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        Text("2. Register a phone number and verify it.", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        Text("3. Set webhook URL to: http://<your-ip>:<port>/webhook/whatsapp", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        Text("4. Use the verify token above when configuring the webhook.", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            Surface(onClick = { onConfirm(name, accessToken.trim(), phoneNumberId.trim(), verifyToken.trim(), allow.trim(), purpose) },
                color = Accent.copy(alpha = 0.15f), border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)
            ) { Text("ADD", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) } }
    )
}
