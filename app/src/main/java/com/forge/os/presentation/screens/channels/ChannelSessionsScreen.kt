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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import com.forge.os.domain.channels.ChannelSession
import com.forge.os.presentation.components.ForgeListRow
import com.forge.os.presentation.components.ForgeScreenScaffold
import com.forge.os.presentation.components.ForgeTopBar
import com.forge.os.presentation.theme.ForgeTokens.Colors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every active live session (one per Telegram chat) with a short
 * preview of the most recent event. Tap into a session to see the full
 * timeline (incoming messages, tool calls, replies, errors) update live.
 */
@Composable
fun ChannelSessionsScreen(
    onBack: () -> Unit,
    onOpen: (sessionKey: String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val ordered = remember(sessions) { sessions.values.sortedByDescending { it.lastActivity } }

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = "LIVE SESSIONS",
                onBack = onBack
            )

            if (ordered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📡", fontSize = 48.sp, modifier = Modifier.alpha(0.3f))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NO ACTIVE SESSIONS",
                            color = Colors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "REACTIVE STREAMS",
                            color = Colors.TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(ordered, key = { it.key }) { s -> 
                        SessionCard(s, onOpen) 
                    }
                    item {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(s: ChannelSession, onOpen: (String) -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val last = s.events.lastOrNull()
    val preview = last?.let { "${it.kind.name}: ${it.content.take(60)}" } ?: "Waiting for events..."
    
    ForgeListRow(
        title = "${s.channelType}:${s.displayName}",
        subtitle = preview,
        icon = Icons.Outlined.Podcasts,
        iconColor = Colors.Accent,
        metaText = fmt.format(Date(s.lastActivity)),
        onClick = { onOpen(s.key) }
    )
}
