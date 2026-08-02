package com.forge.os.presentation.screens.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.forgePalette

private data class ModuleTile(
    val route: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val section: String,
    val keywords: String = "",
)

private val MODULES = listOf(
    // Core
    ModuleTile("tools", Icons.Outlined.Build, "Tools", "Built-ins & permissions", "Core", "tool permission audit"),
    ModuleTile("plugins", Icons.Outlined.Extension, "Plugins", "Install & manage", "Core", "plugin install fp"),
    ModuleTile("cron", Icons.Outlined.Schedule, "Cron", "Scheduled jobs", "Core", "cron scheduled jobs"),
    ModuleTile("memory", Icons.Outlined.Memory, "Memory", "Daily facts & skills", "Core", "memory daily facts"),

    // Agent
    ModuleTile("agents", Icons.Outlined.SmartToy, "Agents", "Sub-agent transcripts", "Agent", "agent sub-agent"),
    ModuleTile("projects", Icons.Outlined.Folder, "Projects", "Scoped workspaces", "Agent", "project workspace"),
    ModuleTile("skills", Icons.Outlined.Code, "Skills", "Reusable Python", "Agent", "skill python"),
    ModuleTile("conversations", Icons.Outlined.Chat, "Chats", "Multi-conversation", "Agent", "chat conversations"),

    // Data
    ModuleTile("snapshots", Icons.Outlined.CameraAlt, "Snapshots", "Workspace backups", "Data", "snapshot backup"),
    ModuleTile("mcp", Icons.Outlined.Hub, "MCP", "External tool servers", "Data", "mcp server"),
    ModuleTile("cost", Icons.Outlined.AttachMoney, "Cost", "Spending & prices", "Data", "cost usage price"),
    ModuleTile("external", Icons.Outlined.Api, "External API", "Other apps using Forge", "Data", "external api intent"),

    // Companion
    ModuleTile("companion", Icons.Outlined.Favorite, "Companion", "Friend mode chat", "Companion", "companion friend"),
    ModuleTile("companionCheckIns", Icons.Outlined.Notifications, "Check-ins", "Proactive reminders", "Companion", "checkin reminder"),
    ModuleTile("companionMemory", Icons.Outlined.Delete, "Companion Memory", "View & delete data", "Companion", "companion memory delete"),

    // Tools
    ModuleTile("browser", Icons.Outlined.Language, "Browser", "Agent-controllable web", "Tools", "browser web"),
    ModuleTile("alarms", Icons.Outlined.Alarm, "Alarms", "Schedule exact alarms", "Tools", "alarm timer"),
    ModuleTile("server", Icons.Outlined.Storage, "Server", "Local HTTP API", "Tools", "server http api"),

    // Advanced
    ModuleTile("debugger", Icons.Outlined.BugReport, "Debugger", "Agent replay traces", "Advanced", "debugger snapshot replay trace"),
    ModuleTile("doctor", Icons.Outlined.MedicalServices, "Doctor", "Diagnostics & repair", "Advanced", "doctor diagnostic repair"),
    ModuleTile("channels", Icons.Outlined.Podcasts, "Channels", "Telegram messaging", "Advanced", "channel telegram messaging"),
    ModuleTile("android", Icons.Outlined.PhoneAndroid, "Android", "Device snapshot", "Advanced", "android device battery"),
)

private val SECTION_ORDER = listOf("Core", "Agent", "Data", "Companion", "Tools", "Advanced")

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
    
    // Tutorial state
    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
    val tutorialManager = tutorialVm.tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    
    // Check if tutorial should be shown
    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowHubTutorial()) {
            kotlinx.coroutines.delay(500)
            showTutorial = true
        }
    }

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
            )
            
            // Search bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .then(com.forge.os.presentation.components.spotlightTarget("hub_search")),
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
            
            // Sectioned module list
            val grouped = remember(visibleBuiltins) {
                SECTION_ORDER.mapNotNull { section ->
                    val items = visibleBuiltins.filter { it.section == section }
                    if (items.isNotEmpty()) section to items else null
                }
            }

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(com.forge.os.presentation.components.spotlightTarget("hub_modules")),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                grouped.forEach { (section, tiles) ->
                    item(key = "header_$section") {
                        Text(
                            section.uppercase(),
                            color = ModernTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                        )
                    }

                    item(key = "grid_$section") {
                        val rows = tiles.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    row.forEach { module ->
                                        ModernModuleTile(
                                            icon = module.icon,
                                            title = module.title,
                                            subtitle = module.subtitle,
                                            onClick = { onNavigate(module.route) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Plugin tiles
                if (visiblePluginTiles.isNotEmpty()) {
                    item(key = "header_plugins") {
                        Text(
                            "PLUGINS",
                            color = ModernTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                        )
                    }

                    item(key = "grid_plugins") {
                        val rows = visiblePluginTiles.chunked(2)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.then(com.forge.os.presentation.components.spotlightTarget("hub_plugins"))
                        ) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    row.forEach { (pluginId, tile) ->
                                        val encoded = java.net.URLEncoder.encode(pluginId, "UTF-8") +
                                            "/" + java.net.URLEncoder.encode(tile.toolName, "UTF-8")
                                        ModernPluginTile(
                                            symbol = tile.symbol,
                                            title = tile.title,
                                            subtitle = tile.subtitle.ifBlank { "plugin: $pluginId" },
                                            onClick = { onNavigate("pluginTile/$encoded") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Tutorial Overlay
        if (showTutorial) {
            val tutorialSteps = listOf(
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Welcome to Hub",
                    description = "This is your control center. All Forge OS modules are organized here by category.",
                    targetKey = null,
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Search",
                    description = "Quickly find any module by typing in the search bar.",
                    targetKey = "hub_search",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Module Tiles",
                    description = "Tap any tile to open that module. Tiles are grouped by category: Core, Agent, Data, Companion, Tools, and Advanced.",
                    targetKey = "hub_modules",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Plugin Tiles",
                    description = "Your installed plugins appear here with their custom icons and actions.",
                    targetKey = "hub_plugins",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
                )
            )
            
            com.forge.os.presentation.components.CoachMarkOverlay(
                steps = tutorialSteps,
                currentStep = tutorialStep,
                onNext = { tutorialStep++ },
                onSkip = {
                    showTutorial = false
                    tutorialManager.markHubTutorialShown()
                },
                onDone = {
                    showTutorial = false
                    tutorialManager.markHubTutorialShown()
                }
            )
        }
    }
}

@Composable
private fun ModernModuleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tileScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) ModernAccent.copy(alpha = 0.5f) else forgePalette.borderSoft,
        animationSpec = tween(150),
        label = "tileBorder"
    )

    Surface(
        onClick = {
            isPressed = true
            onClick()
        },
        modifier = modifier.scale(scale),
        color = ModernSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        ModernAccent.copy(alpha = 0.12f),
                        RoundedCornerShape(11.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = ModernAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    color = ModernTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = ModernSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernAccent.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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


