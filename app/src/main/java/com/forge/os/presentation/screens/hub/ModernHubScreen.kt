package com.forge.os.presentation.screens.hub

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.*

private data class ModuleTile(
    val route: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val color: Color,
    val keywords: String = "",
    val isNew: Boolean = false,
)

private val MODULES = listOf(
    // Core Features
    ModuleTile("tools", Icons.Outlined.Build, "Tools", "Built-ins & permissions", Color(0xFF8B5CF6), "tool permission audit"),
    ModuleTile("plugins", Icons.Outlined.Extension, "Plugins", "Install & manage", Color(0xFFEC4899), "plugin install fp"),
    ModuleTile("cron", Icons.Outlined.Schedule, "Cron", "Scheduled jobs", Color(0xFF06B6D4), "cron scheduled jobs"),
    ModuleTile("memory", Icons.Outlined.Memory, "Memory", "Daily facts & skills", Color(0xFFEF4444), "memory daily facts"),
    
    // Agent Features
    ModuleTile("agents", Icons.Outlined.SmartToy, "Agents", "Sub-agent transcripts", Color(0xFF10B981), "agent sub-agent"),
    ModuleTile("projects", Icons.Outlined.Folder, "Projects", "Scoped workspaces", Color(0xFFF59E0B), "project workspace"),
    ModuleTile("skills", Icons.Outlined.Code, "Skills", "Reusable Python", Color(0xFF3B82F6), "skill python"),
    ModuleTile("conversations", Icons.Outlined.Chat, "Chats", "Multi-conversation", Color(0xFF8B5CF6), "chat conversations"),
    
    // Data & Storage
    ModuleTile("snapshots", Icons.Outlined.CameraAlt, "Snapshots", "Workspace backups", Color(0xFF06B6D4), "snapshot backup"),
    ModuleTile("mcp", Icons.Outlined.Hub, "MCP", "External tool servers", Color(0xFFEC4899), "mcp server"),
    ModuleTile("cost", Icons.Outlined.AttachMoney, "Cost", "Spending & prices", Color(0xFF22C55E), "cost usage price"),
    ModuleTile("external", Icons.Outlined.Api, "External API", "Other apps using Forge", Color(0xFF3B82F6), "external api intent"),
    
    // Companion Features
    ModuleTile("companion", Icons.Outlined.Favorite, "Companion", "Friend mode chat", Color(0xFFEF4444), "companion friend"),
    ModuleTile("companionCheckIns", Icons.Outlined.Notifications, "Check-ins", "Proactive reminders", Color(0xFFF59E0B), "checkin reminder"),
    ModuleTile("companionMemory", Icons.Outlined.Delete, "Companion Memory", "View & delete data", Color(0xFF6B7280), "companion memory delete"),
    
    // Tools & Utilities
    ModuleTile("browser", Icons.Outlined.Language, "Browser", "Agent-controllable web", Color(0xFF3B82F6), "browser web"),
    ModuleTile("alarms", Icons.Outlined.Alarm, "Alarms", "Schedule exact alarms", Color(0xFFF59E0B), "alarm timer", isNew = true),
    ModuleTile("server", Icons.Outlined.Storage, "Server", "Local HTTP API", Color(0xFF8B5CF6), "server http api", isNew = true),
    
    // Advanced Features
    ModuleTile("debugger", Icons.Outlined.BugReport, "Debugger", "Agent replay traces", Color(0xFFEF4444), "debugger snapshot replay trace", isNew = true),
    ModuleTile("doctor", Icons.Outlined.MedicalServices, "Doctor", "Diagnostics & repair", Color(0xFF22C55E), "doctor diagnostic repair", isNew = true),
    ModuleTile("channels", Icons.Outlined.Podcasts, "Channels", "Telegram messaging", Color(0xFF06B6D4), "channel telegram messaging", isNew = true),
    ModuleTile("android", Icons.Outlined.PhoneAndroid, "Android", "Device snapshot", Color(0xFF10B981), "android device battery", isNew = true),
)

/**
 * Modern hub screen - main dashboard for all Forge OS modules.
 * Features:
 * - Grid layout with colorful module cards
 * - Search functionality
 * - Quick actions FAB
 * - Plugin tiles integration
 * - Smooth animations
 */
@Composable
fun ModernHubScreen(
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModernBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Modern Header
            ModernHeader(
                title = "Modules",
                subtitle = "${visibleBuiltins.size + visiblePluginTiles.size} available",
                onBackClick = onBack
            ) {
                IconButton(
                    onClick = { onNavigate("settings") },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        "Settings",
                        tint = ModernTextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            // Search bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                color = ModernSurface,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = ModernTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                "Search modules...",
                                color = ModernTextSecondary,
                                fontSize = 14.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = ModernTextPrimary,
                            unfocusedTextColor = ModernTextPrimary,
                            cursorColor = ModernAccent
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                "Clear",
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            
            // Module grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visibleBuiltins) { module ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f)
                    ) {
                        ModernModuleTile(
                            icon = module.icon,
                            title = module.title,
                            subtitle = module.subtitle,
                            color = module.color,
                            isNew = module.isNew,
                            onClick = { onNavigate(module.route) }
                        )
                    }
                }
                
                items(visiblePluginTiles) { (pluginId, tile) ->
                    val encoded = java.net.URLEncoder.encode(pluginId, "UTF-8") +
                        "/" + java.net.URLEncoder.encode(tile.toolName, "UTF-8")
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f)
                    ) {
                        ModernPluginTile(
                            symbol = tile.symbol,
                            title = tile.title,
                            subtitle = tile.subtitle.ifBlank { "plugin: $pluginId" },
                            onClick = { onNavigate("pluginTile/$encoded") }
                        )
                    }
                }
            }
        }
        
        // Quick Actions FAB
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionButton(
                        label = "New Chat",
                        icon = Icons.Outlined.Add,
                        onClick = {
                            onNavigate("conversations")
                            fabExpanded = false
                        }
                    )
                    QuickActionButton(
                        label = "Diagnostics",
                        icon = Icons.Outlined.MonitorHeart,
                        onClick = {
                            onNavigate("doctor")
                            fabExpanded = false
                        }
                    )
                    QuickActionButton(
                        label = "Clear Trace",
                        icon = Icons.Outlined.CleaningServices,
                        onClick = {
                            onNavigate("debugger")
                            fabExpanded = false
                        }
                    )
                }
            }
            
            val rotation by animateFloatAsState(
                targetValue = if (fabExpanded) 45f else 0f,
                label = "fab_rotation"
            )
            
            FloatingActionButton(
                onClick = { fabExpanded = !fabExpanded },
                containerColor = ModernAccent,
                contentColor = Color.White,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = "Quick Actions",
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation)
                )
            }
        }
    }
}

@Composable
private fun ModernModuleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    isNew: Boolean,
    onClick: () -> Unit
) {
    ModernCard(onClick = onClick) {
        Column(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                if (isNew) {
                    Surface(
                        color = ModernAccent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "NEW",
                            color = ModernAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    color = ModernTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun ModernPluginTile(
    symbol: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernAccent.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .height(120.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    symbol,
                    fontSize = 24.sp
                )
                
                Surface(
                    color = ModernAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "PLUGIN",
                        color = ModernAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    color = ModernTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = ModernTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                icon,
                contentDescription = null,
                tint = ModernAccent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
