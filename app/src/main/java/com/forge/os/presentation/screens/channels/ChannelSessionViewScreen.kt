package com.forge.os.presentation.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.channels.ChannelManager
import com.forge.os.domain.channels.SessionEvent
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModelPickerDialog
import com.forge.os.presentation.screens.common.ModelPickerRow
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live timeline for a single channel session. Auto-scrolls to the bottom
 * as new events stream in. Each event kind gets its own colour:
 *   • IncomingText / IncomingAttachment — neutral white card (user → bot)
 *   • OutgoingText / OutgoingVoice      — orange card (bot → user)
 *   • ChatAction                        — italic muted ("typing…")
 *   • Thinking                          — italic muted partial text
 *   • ToolCall                          — yellow card with tool name
 *   • ToolResult                        — green card (red on error)
 *   • AgentError                        — red card
 *
 * Phase U2 adds an inline model picker. The selected provider+model is
 * tried FIRST for this chat's auto-replies; if it fails, ChannelManager
 * silently falls back to the global default route. The picker is a pure
 * select-list — both built-in providers AND user-defined custom endpoints
 * appear, each grouped under its provider with its live `/models` catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSessionViewScreen(
    sessionKey: String,
    onBack: () -> Unit,
    onSendReply: (text: String) -> Unit = {},
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val session = sessions[sessionKey]
    var manualText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Per-session model override state.
    var showModelPicker by remember { mutableStateOf(false) }
    var sessionOverride by remember(sessionKey) {
        mutableStateOf(viewModel.getSessionModel(sessionKey))
    }

    LaunchedEffect(session?.events?.size ?: 0) {
        val n = session?.events?.size ?: 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    ModuleScaffold(
        title = session?.let { "${it.channelType}:${it.displayName}".uppercase() } ?: "SESSION",
        onBack = onBack,
    ) {
        if (session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Session ended.", color = forgePalette.textMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            return@ModuleScaffold
        }

        Column(Modifier.fillMaxSize()) {
            Text("chat ${session.chatId}  ·  ${session.events.size} events",
                color = forgePalette.textMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

            // ─── Phase U2: inline model picker ─────────────────────────
            ModelPickerRow(
                override = sessionOverride,
                onClick = { showModelPicker = true },
                onClear = {
                    viewModel.setSessionModel(sessionKey, "", "")
                    sessionOverride = null
                },
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(session.events.size) { idx ->
                    EventRow(session.events[idx])
                }
            }
            // Manual reply box (lets the user jump in mid-conversation).
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    placeholder = { Text("Send a manual reply") },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (manualText.isNotBlank()) {
                            onSendReply(manualText); manualText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = forgePalette.orange),
                ) { Text("SEND", fontFamily = FontFamily.Monospace) }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            title = "Pick model for this chat",
            availableModels = { viewModel.availableModels() },
            initial = sessionOverride,
            onDismiss = { showModelPicker = false },
            onSave = { providerKey, model ->
                viewModel.setSessionModel(sessionKey, providerKey, model)
                sessionOverride = if (providerKey.isBlank() || model.isBlank()) null else providerKey to model
                showModelPicker = false
            },
        )
    }
}

@Composable
private fun EventRow(e: SessionEvent) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val (bg, fg, label) = when (e.kind) {
        SessionEvent.Kind.IncomingText,
        SessionEvent.Kind.IncomingAttachment ->
            Triple(forgePalette.surface, forgePalette.textPrimary, "← ${e.kind.name}")
        SessionEvent.Kind.OutgoingText,
        SessionEvent.Kind.OutgoingVoice,
        SessionEvent.Kind.OutgoingAttachment ->
            Triple(forgePalette.orange.copy(alpha = 0.15f),
                forgePalette.textPrimary, "→ ${e.kind.name}")
        SessionEvent.Kind.ChatAction ->
            Triple(Color.Transparent, forgePalette.textMuted, "•")
        SessionEvent.Kind.Thinking ->
            Triple(Color.Transparent, forgePalette.textMuted, "thinking")
        SessionEvent.Kind.ToolCall ->
            Triple(Color(0xFF3A2F00), Color(0xFFFFD24A),
                "🔧 ${e.toolName ?: "tool"}")
        SessionEvent.Kind.ToolResult ->
            if (e.isError) Triple(Color(0xFF3A0000), Color(0xFFFF6B6B),
                "✗ ${e.toolName ?: "tool"}")
            else Triple(Color(0xFF062B0E), Color(0xFF6BD68A),
                "✓ ${e.toolName ?: "tool"}")
        SessionEvent.Kind.AgentError ->
            Triple(Color(0xFF3A0000), Color(0xFFFF6B6B), "⚠️ error")
        SessionEvent.Kind.Info ->
            Triple(forgePalette.surface2, forgePalette.textMuted, "i")
    }
    Column(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row {
            Text(label, color = fg, fontFamily = FontFamily.Monospace,
                fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(fmt.format(Date(e.timestamp)), color = forgePalette.textMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (e.content.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            val isItalic = e.kind == SessionEvent.Kind.Thinking ||
                e.kind == SessionEvent.Kind.ChatAction
            Text(
                e.content,
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}
