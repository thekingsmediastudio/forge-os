package com.forge.os.presentation.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.channels.ChannelSession
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every active live session (one per Telegram chat) with a short
 * preview of the most recent event. Tap into a session to see the full
 * timeline (incoming messages, tool calls, replies, errors) update live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSessionsScreen(
    onBack: () -> Unit,
    onOpen: (sessionKey: String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val ordered = remember(sessions) { sessions.values.sortedByDescending { it.lastActivity } }

    ModuleScaffold(title = "LIVE SESSIONS", onBack = onBack) {
        if (ordered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No active sessions yet.",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("Send your bot a message in Telegram to start one.",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ordered, key = { it.key }) { s -> SessionCard(s, onOpen) }
            }
        }
    }
}

@Composable
private fun SessionCard(s: ChannelSession, onOpen: (String) -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val last = s.events.lastOrNull()
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .clickable { onOpen(s.key) }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📡  ${s.channelType}:${s.displayName}",
                color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            Text(fmt.format(Date(s.lastActivity)),
                color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text("chat ${s.chatId}  ·  ${s.events.size} events",
            color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        if (last != null) {
            Spacer(Modifier.height(4.dp))
            Text("${last.kind.name}: ${last.content.take(120).replace('\n', ' ')}",
                color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
