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
import com.forge.os.domain.channels.SessionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.text.font.FontWeight
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.ForgeTokens.Colors
import com.forge.os.presentation.screens.common.ModelPickerDialog
import com.forge.os.presentation.screens.common.ModelPickerRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live timeline for a single channel session. Auto-scrolls to the bottom
 * as new events stream in.
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

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = session?.let { "${it.channelType}:${it.displayName}".uppercase() } ?: "SESSION",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.Tune, "Model Settings", tint = Colors.TextPrimary)
                    }
                }
            )

            if (session == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Session ended.", color = Colors.TextDim,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                return@ForgeScreenScaffold
            }

            Column(Modifier.fillMaxSize()) {
                // Info Subheader
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        "METRICS: ${session.chatId} · ${session.events.size} EVENTS",
                        color = Colors.TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }

                // Inline model picker
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    ModelPickerRow(
                        override = sessionOverride,
                        onClick = { showModelPicker = true },
                        onClear = {
                            viewModel.setSessionModel(sessionKey, "", "")
                            sessionOverride = null
                        },
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(session.events.size) { idx ->
                        EventRow(session.events[idx])
                    }
                }

                // Manual reply box
                ForgeCard(
                    padding = PaddingValues(12.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = manualText,
                            onValueChange = { manualText = it },
                            placeholder = { Text("Directive Response...", color = Colors.TextSecondary, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Colors.Accent,
                                unfocusedBorderColor = Colors.Border,
                                focusedTextColor = Colors.TextPrimary,
                                unfocusedTextColor = Colors.TextPrimary
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        ForgeButton(
                            text = "SEND",
                            onClick = {
                                if (manualText.isNotBlank()) {
                                    onSendReply(manualText)
                                    manualText = ""
                                }
                            }
                        )
                    }
                }
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
            Triple(Colors.BgSurface, Colors.TextPrimary, "← ${e.kind.name}")
        SessionEvent.Kind.OutgoingText,
        SessionEvent.Kind.OutgoingVoice,
        SessionEvent.Kind.OutgoingAttachment ->
            Triple(Colors.Accent.copy(alpha = 0.1f), Colors.TextPrimary, "→ ${e.kind.name}")
        SessionEvent.Kind.ChatAction ->
            Triple(Color.Transparent, Colors.TextDim, "•")
        SessionEvent.Kind.Thinking ->
            Triple(Color.Transparent, Colors.TextDim, "thinking")
        SessionEvent.Kind.ToolCall ->
            Triple(Color(0xFF332A00), Color(0xFFFFD700), "🔧 ${e.toolName ?: "tool"}")
        SessionEvent.Kind.ToolResult ->
            if (e.isError) Triple(Color(0xFF330000), Colors.Error, "✗ ${e.toolName ?: "tool"}")
            else Triple(Color(0xFF002200), Colors.Success, "✓ ${e.toolName ?: "tool"}")
        SessionEvent.Kind.AgentError ->
            Triple(Color(0xFF330000), Colors.Error, "⚠️ error")
        SessionEvent.Kind.Info ->
            Triple(Colors.BgSurface, Colors.TextDim, "i")
    }
    
    ForgeCard(
        padding = PaddingValues(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row {
                Text(label, color = fg, fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(fmt.format(Date(e.timestamp)), color = Colors.TextDim,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            if (e.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                val isItalic = e.kind == SessionEvent.Kind.Thinking ||
                    e.kind == SessionEvent.Kind.ChatAction
                Text(
                    e.content,
                    color = fg,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
    }
}
