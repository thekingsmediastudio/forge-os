package com.forge.os.presentation.screens.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector

private data class ModuleTile(
    val route: String,
    val symbol: String,
    val title: String,
    val subtitle: String,
    val keywords: String = "",
)

private val MODULES = listOf(
    ModuleTile("tools",             "⚙",  "Tools",            "Built-ins, perms, audit", "tool permission audit"),
    ModuleTile("plugins",           "🧩", "Plugins",          "Install + manage", "plugin install fp"),
    ModuleTile("cron",              "⏰", "Cron",             "Scheduled jobs", "cron scheduled jobs"),
    ModuleTile("memory",            "🧠", "Memory",           "Daily / facts / skills", "memory daily facts"),
    ModuleTile("agents",            "🤖", "Agents",           "Sub-agent transcripts", "agent sub-agent"),
    ModuleTile("projects",          "📂", "Projects",         "Scoped workspaces", "project workspace"),
    ModuleTile("skills",            "🛠", "Skills",           "Reusable Python", "skill python"),
    ModuleTile("conversations",     "💬", "Chats",            "Multi-conversation", "chat conversations"),
    ModuleTile("snapshots",         "📦", "Snapshots",        "Workspace backups", "snapshot backup"),
    ModuleTile("mcp",               "🔌", "MCP",              "External tool servers", "mcp server"),
    ModuleTile("cost",              "💰", "Cost",             "Spending + prices", "cost usage price"),
    ModuleTile("external",          "🌐", "External API",     "Other apps using Forge", "external api intent"),
    ModuleTile("companion",         "💛", "Companion",        "Friend mode chat", "companion friend"),
    ModuleTile("companionCheckIns", "🔔", "Check-ins",        "Proactive check-ins", "checkin reminder"),
    ModuleTile("companionMemory",   "🗑", "Companion Memory", "View & delete stored data", "companion memory delete"),
    ModuleTile("browser",           "🌐", "Browser",          "Persistent in-app browser — agent-controllable", "browser web"),
    // New in this release ─────────────────────────────────────────────
    ModuleTile("alarms",            "⏱", "Alarms",           "Schedule exact alarms", "alarm timer"),
    ModuleTile("server",            "🖧", "Server",           "Local HTTP API for your tools", "server http api"),
    ModuleTile("debugger",          "🔍", "Debugger",         "Agent replay traces", "debugger snapshot replay trace"),
    ModuleTile("doctor",            "🩺", "Doctor",           "Diagnostics + auto-repair", "doctor diagnostic repair"),
    ModuleTile("channels",          "📡", "Channels",         "Telegram & multi-channel messaging", "channel telegram messaging"),
    ModuleTile("android",           "📱", "Android",          "Device snapshot (battery, apps, net)", "android device battery"),
)

@Composable
fun HubScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: HubViewModel = hiltViewModel(),
) {
    var query by remember { mutableStateOf("") }
    val pluginTiles by viewModel.pluginTiles.collectAsState()
    var fabExpanded by remember { mutableStateOf(false) }

    val q = query.trim().lowercase()
    val visibleBuiltins = if (q.isEmpty()) MODULES else MODULES.filter {
        it.title.lowercase().contains(q) ||
            it.subtitle.lowercase().contains(q) ||
            it.keywords.lowercase().contains(q)
    }
    val visiblePluginTiles = if (q.isEmpty()) pluginTiles else pluginTiles.filter { (_, t) ->
        t.title.lowercase().contains(q) || t.subtitle.lowercase().contains(q)
    }

    ModuleScaffold(title = "MODULES", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search modules…",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(visibleBuiltins) { mod ->
                    Tile(mod.symbol, mod.title, mod.subtitle,
                        accent = false, onClick = { onNavigate(mod.route) })
                }
                items(visiblePluginTiles) { (pluginId, tile) ->
                    val encoded = java.net.URLEncoder.encode(pluginId, "UTF-8") +
                        "/" + java.net.URLEncoder.encode(tile.toolName, "UTF-8")
                    Tile(tile.symbol, tile.title, tile.subtitle.ifBlank { "plugin: $pluginId" },
                        accent = true, onClick = { onNavigate("pluginTile/$encoded") })
                }
            }

    // Quick Action FAB
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    QuickActionItem("New Chat", Icons.Default.Add, onClick = { onNavigate("conversations"); fabExpanded = false })
                    Spacer(Modifier.height(8.dp))
                    QuickActionItem("Diagnostics", Icons.Default.MonitorHeart, onClick = { onNavigate("doctor"); fabExpanded = false })
                    Spacer(Modifier.height(8.dp))
                    QuickActionItem("Clear Trace", Icons.Default.CleaningServices, onClick = { onNavigate("debugger"); fabExpanded = false })
                    Spacer(Modifier.height(16.dp))
                }
            }
            
            val rotation by animateFloatAsState(if (fabExpanded) 45f else 0f)
            
            FloatingActionButton(
                onClick = { fabExpanded = !fabExpanded },
                containerColor = forgePalette.orange,
                contentColor = Color.Black,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    Icons.Default.Bolt, 
                    contentDescription = "Actions",
                    modifier = Modifier.rotate(rotation)
                )
            }
        }
    }
        }
    }
}

@Composable
private fun QuickActionItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(forgePalette.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label.uppercase(),
            color = forgePalette.textPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.width(12.dp))
        Icon(icon, null, tint = forgePalette.orange, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Tile(symbol: String, title: String, subtitle: String, accent: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(110.dp)
            .background(forgePalette.surface, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (accent) forgePalette.orange else forgePalette.border,
                RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = forgePalette.textPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Text(subtitle, color = forgePalette.textMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
