package com.forge.os.presentation.screens.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.forgePalette

private const val HUB_PREFS = "forge_hub_prefs"
private const val KEY_FAVORITES = "favorite_routes"
private const val KEY_RECENTS = "recent_routes"
private const val MAX_RECENTS = 6

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
    ModuleTile("recipes", Icons.Outlined.MenuBook, "Recipes", "Prompt templates", "Agent", "recipe prompt template"),
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

    // Security
    ModuleTile("findMyPhone", Icons.Outlined.PhonePaused, "Find My Phone", "Locate your lost phone", "Security", "find my phone lost locate ring"),
    ModuleTile("antiTheft", Icons.Outlined.Security, "Anti-Theft", "Grab & run protection", "Security", "antitheft theft lock wipe locate"),

    // Advanced
    ModuleTile("debugger", Icons.Outlined.BugReport, "Debugger", "Agent replay traces", "Advanced", "debugger snapshot replay trace"),
    ModuleTile("doctor", Icons.Outlined.MedicalServices, "Doctor", "Diagnostics & repair", "Advanced", "doctor diagnostic repair"),
    ModuleTile("channels", Icons.Outlined.Podcasts, "Channels", "Telegram messaging", "Advanced", "channel telegram messaging"),
    ModuleTile("android", Icons.Outlined.PhoneAndroid, "Android", "Device snapshot", "Advanced", "android device battery"),
)

private val SECTION_ORDER = listOf("Core", "Agent", "Data", "Companion", "Tools", "Security", "Advanced")

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
    val enabledCronCount by viewModel.enabledCronCount.collectAsState()
    val snapshotCount by viewModel.snapshotCount.collectAsState()
    val dailyUsd by viewModel.dailyUsd.collectAsState()

    // Favorites & recents (SharedPreferences-backed)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HUB_PREFS, android.content.Context.MODE_PRIVATE) }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet())
    }
    var recents by remember {
        mutableStateOf(
            (prefs.getString(KEY_RECENTS, "") ?: "")
                .split(",").filter { it.isNotBlank() }
        )
    }

    fun toggleFavorite(route: String) {
        favorites = if (route in favorites) favorites - route else favorites + route
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun recordRecent(route: String) {
        recents = (listOf(route) + recents.filter { it != route }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, recents.joinToString(",")).apply()
    }

    // Live status badges per route
    val statusBadges: Map<String, String> = mapOf(
        "cron" to if (enabledCronCount > 0) "$enabledCronCount active" else "",
        "cost" to if (dailyUsd > 0.0) "${"%.2f".format(dailyUsd)} today" else "",
        "snapshots" to if (snapshotCount > 0) "$snapshotCount saved" else "",
        "plugins" to if (pluginTiles.isNotEmpty()) "${pluginTiles.size} installed" else "",
    ).filterValues { it.isNotEmpty() }

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

    val favoriteTiles = remember(visibleBuiltins, favorites) {
        visibleBuiltins.filter { it.route in favorites }
    }
    val recentTiles = remember(recents, visibleBuiltins) {
        recents.mapNotNull { route -> visibleBuiltins.firstOrNull { it.route == route } }
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
            
            // Search bar (shared chat-style component)
            ForgeSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search modules…",
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .spotlightTarget("hub_search")
            )

            // Recent modules chips (hidden while searching)
            if (q.isEmpty() && recentTiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RECENT",
                        color = ModernTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    recentTiles.forEach { module ->
                        Surface(
                            onClick = {
                                recordRecent(module.route)
                                onNavigate(module.route)
                            },
                            color = forgePalette.surface2,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    module.icon,
                                    contentDescription = null,
                                    tint = ModernAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    module.title,
                                    color = ModernTextPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Sectioned module list
            val grouped = remember(visibleBuiltins, favorites) {
                SECTION_ORDER.mapNotNull { section ->
                    val items = visibleBuiltins.filter { it.section == section && it.route !in favorites }
                    if (items.isNotEmpty()) section to items else null
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .spotlightTarget("hub_modules"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Favorites section (hidden while searching)
                if (q.isEmpty() && favoriteTiles.isNotEmpty()) {
                    item(key = "header_favorites", span = { GridItemSpan(maxLineSpan) }) {
                        HubSectionHeader(
                            title = "FAVORITES",
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                    }
                    items(favoriteTiles, key = { "fav_${it.route}" }) { module ->
                        ModernModuleTile(
                            icon = module.icon,
                            title = module.title,
                            subtitle = module.subtitle,
                            badge = statusBadges[module.route],
                            isFavorite = true,
                            onToggleFavorite = { toggleFavorite(module.route) },
                            onClick = {
                                recordRecent(module.route)
                                onNavigate(module.route)
                            }
                        )
                    }
                }

                grouped.forEach { (section, tiles) ->
                    item(key = "header_$section", span = { GridItemSpan(maxLineSpan) }) {
                        HubSectionHeader(
                            title = section.uppercase(),
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                    }

                    items(tiles, key = { "mod_${it.route}" }) { module ->
                        ModernModuleTile(
                            icon = module.icon,
                            title = module.title,
                            subtitle = module.subtitle,
                            badge = statusBadges[module.route],
                            isFavorite = module.route in favorites,
                            onToggleFavorite = { toggleFavorite(module.route) },
                            onClick = {
                                recordRecent(module.route)
                                onNavigate(module.route)
                            }
                        )
                    }
                }

                // Plugin tiles
                if (visiblePluginTiles.isNotEmpty()) {
                    item(key = "header_plugins", span = { GridItemSpan(maxLineSpan) }) {
                        HubSectionHeader(
                            title = "PLUGINS",
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                    }

                    items(visiblePluginTiles, key = { "plugin_${it.first}" }) { (pluginId, tile) ->
                        val encoded = java.net.URLEncoder.encode(pluginId, "UTF-8") +
                            "/" + java.net.URLEncoder.encode(tile.toolName, "UTF-8")
                        Box(modifier = Modifier.spotlightTarget("hub_plugins")) {
                            ModernPluginTile(
                                symbol = tile.symbol,
                                title = tile.title,
                                subtitle = tile.subtitle.ifBlank { "plugin: $pluginId" },
                                onClick = {
                                    recordRecent("plugins")
                                    onNavigate("pluginTile/$encoded")
                                }
                            )
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
private fun HubSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        color = ModernTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = modifier
    )
}

@Composable
private fun ModernModuleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
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
                if (onToggleFavorite != null) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorite) "Unpin favorite" else "Pin favorite",
                        tint = if (isFavorite) forgePalette.warning else forgePalette.textDim,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onToggleFavorite() }
                    )
                }
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
                if (badge != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = forgePalette.success.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            badge,
                            color = forgePalette.success,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
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


