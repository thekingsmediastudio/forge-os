package com.forge.os.presentation

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.presentation.screens.BrowserScreen
import com.forge.os.presentation.screens.ChatScreen
import com.forge.os.presentation.screens.DebuggerOverlay
import com.forge.os.presentation.screens.DiagnosticsScreen
import com.forge.os.presentation.screens.FileViewerScreen
import com.forge.os.presentation.screens.debugger.DebuggerScreen
import com.forge.os.presentation.screens.OnboardingScreen
import com.forge.os.presentation.screens.OnboardingViewModel
import com.forge.os.presentation.screens.SettingsScreen
import com.forge.os.presentation.screens.StatusScreen
import com.forge.os.presentation.screens.WorkspaceScreen
import com.forge.os.presentation.screens.agents.AgentsScreen
import com.forge.os.presentation.screens.alarms.AlarmsScreen
import com.forge.os.presentation.screens.android.AndroidScreen
import com.forge.os.presentation.screens.channels.ChannelsScreen
import com.forge.os.presentation.screens.channels.ChannelSessionsScreen
import com.forge.os.presentation.screens.channels.ChannelSessionViewScreen
import com.forge.os.presentation.screens.doctor.DoctorScreen
import com.forge.os.presentation.screens.plugins.PluginTileScreen
import com.forge.os.presentation.screens.server.ServerScreen
import com.forge.os.presentation.screens.conversations.ConversationsScreen
import com.forge.os.presentation.screens.companion.CompanionCheckInsScreen
import com.forge.os.presentation.screens.companion.CompanionConversationsScreen
import com.forge.os.presentation.screens.companion.CompanionMemoryScreen
import com.forge.os.presentation.screens.companion.CompanionScreen
import com.forge.os.presentation.screens.companion.PendingCompanionSeed
import com.forge.os.presentation.screens.companion.PersonaScreen
import com.forge.os.presentation.screens.cost.CostStatsScreen
import com.forge.os.presentation.screens.cron.CronScreen
import com.forge.os.presentation.screens.cron.CronSessionScreen
import com.forge.os.presentation.screens.external.ExternalApiScreen
import com.forge.os.presentation.screens.hub.ModernHubScreen
import com.forge.os.presentation.screens.mcp.McpServersScreen
import com.forge.os.presentation.screens.memory.MemoryScreen
import com.forge.os.presentation.screens.plugins.PluginsScreen
import com.forge.os.presentation.screens.projects.ProjectsScreen
import com.forge.os.presentation.screens.skills.SkillsScreen
import com.forge.os.presentation.screens.snapshots.SnapshotsScreen
import com.forge.os.presentation.screens.tools.ToolsScreen
import com.forge.os.data.android.AutoPhoneConnection
import com.forge.os.presentation.theme.ForgeTheme
import com.forge.os.presentation.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var sandboxManager: SandboxManager
    @Inject lateinit var autoPhoneConnection: AutoPhoneConnection

    private val requestAutoPhoneControl =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) autoPhoneConnection.connect()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** Batch request for all dangerous permissions the tool providers need. */
    private val requestDangerousPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op — providers check at call time */ }

    /**
     * Pending in-app navigation request, populated by [consumeIntentExtras]
     * whenever the activity is started or re-delivered (e.g. by a notification
     * tap). The Compose tree observes this and runs the navigation as a
     * side-effect, then clears the value.
     *
     * Without this, the original code only honoured the `nav` extra at the
     * very first composition. Tapping a notification while the app was
     * already running re-fired onNewIntent but never moved the user.
     */
    private val pendingNavRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureAutoPhoneControlPermission()
        ensureNotificationPermission()
        ensureDangerousPermissions()
        consumeIntentExtras(intent)
        setContent {
            val themeMode by configRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            ForgeTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val context = LocalContext.current
                    val nav = intent?.getStringExtra("nav")
                    val startDest = when {
                        !OnboardingViewModel.isOnboarded(context) -> "onboarding"
                        nav == "companion" -> "companion"
                        nav == "external"  -> "external"
                        nav == "chat"      -> "chat"
                        else -> "chat"
                    }

                    // Honour subsequent intent updates (notification taps while
                    // the activity is alive). Only navigate to known routes;
                    // unknown values are dropped.
                    LaunchedEffect(navController) {
                        pendingNavRequest.collectLatest { route ->
                            if (route.isNullOrBlank()) return@collectLatest
                            if (route !in KNOWN_NAV_ROUTES) {
                                pendingNavRequest.value = null
                                return@collectLatest
                            }
                            // Don't push a duplicate of the current destination.
                            val current = navController.currentDestination?.route
                            if (current != route) {
                                runCatching {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            pendingNavRequest.value = null
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = startDest
                    ) {
                        composable("onboarding") {
                            com.forge.os.presentation.screens.ModernOnboardingScreen(
                                onDone = {
                                    navController.navigate("chat") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("chat") {
                            com.forge.os.presentation.screens.chat.ModernChatScreen(
                                onNavigateToWorkspace = { navController.navigate("workspace") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToStatus = { navController.navigate("status") },
                                onNavigateToHub = { navController.navigate("hub") },
                                onNavigateToCompanion = { navController.navigate("companion") },
                                onNavigateToConversations = { navController.navigate("conversations") },
                                onNavigateToBrowser = { navController.navigate("browser") },
                            )
                        }
                        composable("hub") {
                            com.forge.os.presentation.screens.hub.ModernHubScreen(
                                onBack = { navController.popBackStack() },
                                onNavigate = { route -> navController.navigate(route) },
                            )
                        }
                        composable("tools")     { ToolsScreen(onBack = { navController.popBackStack() }) }
                        composable("plugins")   { PluginsScreen(onBack = { navController.popBackStack() }) }
                        composable("cron")      {
                            CronScreen(
                                onBack = { navController.popBackStack() },
                                onOpenSession = { navController.navigate("cronSession") },
                            )
                        }
                        composable("cronSession") {
                            CronSessionScreen(onBack = { navController.popBackStack() })
                        }
                        composable("memory")    { MemoryScreen(onBack = { navController.popBackStack() }) }
                        composable("agents")    { AgentsScreen(onBack = { navController.popBackStack() }) }
                        composable("projects")  { ProjectsScreen(onBack = { navController.popBackStack() }) }
                        composable("skills")    { SkillsScreen(onBack = { navController.popBackStack() }) }
                        composable("snapshots") { SnapshotsScreen(onBack = { navController.popBackStack() }) }
                        composable("debugger") { DebuggerScreen(onBack = { navController.popBackStack() }) }
                        composable("mcp")       { McpServersScreen(onBack = { navController.popBackStack() }) }
                        composable("browser")   {
                            BrowserScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("cost")      { CostStatsScreen(onBack = { navController.popBackStack() }) }
                        composable("external")  { ExternalApiScreen(onBack = { navController.popBackStack() }) }
                        composable("companion") {
                            CompanionScreen(
                                onBack = { navController.popBackStack() },
                                onOpenPersona = { navController.navigate("persona") },
                                onSwitchToAgent = {
                                    navController.navigate("chat") {
                                        popUpTo("chat") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onOpenHistory = { navController.navigate("companionConversations") },
                            )
                        }
                        composable("persona") {
                            PersonaScreen(onBack = { navController.popBackStack() })
                        }
                        composable("companionCheckIns") {
                            CompanionCheckInsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("companionMemory") {
                            CompanionMemoryScreen(onBack = { navController.popBackStack() })
                        }
                        composable("companionConversations") {
                            CompanionConversationsScreen(
                                onBack = { navController.popBackStack() },
                                onOpened = {
                                    navController.popBackStack("companion", inclusive = false)
                                },
                            )
                        }
                        composable("conversations") {
                            ConversationsScreen(
                                onBack = { navController.popBackStack() },
                                onOpened = {
                                    navController.popBackStack("chat", inclusive = false)
                                },
                            )
                        }
                        composable("workspace") {
                            WorkspaceScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onOpenFile = { rel ->
                                    val encoded = URLEncoder.encode(rel, Charsets.UTF_8.name())
                                    navController.navigate("fileViewer/$encoded")
                                },
                                sandboxManager = sandboxManager,
                            )
                        }
                        composable(
                            "fileViewer/{path}",
                            arguments = listOf(navArgument("path") { type = NavType.StringType }),
                        ) { entry ->
                            val raw = entry.arguments?.getString("path").orEmpty()
                            val decoded = URLDecoder.decode(raw, Charsets.UTF_8.name())
                            FileViewerScreen(
                                path = decoded,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                                onNavigateToModelRouting = { navController.navigate("modelRouting") },
                                onNavigateToOverrides = { navController.navigate("toolOverrides") },
                                onNavigateToBackup = { navController.navigate("backup") },
                                onNavigateToPersonality = { navController.navigate("personality") },
                            )
                        }
                        composable("personality") {
                            com.forge.os.presentation.screens.settings.PersonalityScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("modelRouting") {
                            com.forge.os.presentation.screens.settings.ModelRoutingScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("toolOverrides") {
                            com.forge.os.presentation.screens.tools.AdvancedOverridesScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("status") {
                            com.forge.os.presentation.screens.ModernStatusScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("diagnostics") {
                            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("backup") {
                            com.forge.os.presentation.screens.settings.BackupScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("alarms")   { AlarmsScreen(onBack = { navController.popBackStack() }) }
                        composable("server")   { ServerScreen(onBack = { navController.popBackStack() }) }
                        composable("doctor")   { DoctorScreen(onBack = { navController.popBackStack() }) }
                        composable("channels") {
                            ChannelsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenSessions = { navController.navigate("channelSessions") },
                            )
                        }
                        composable("channelSessions") {
                            ChannelSessionsScreen(
                                onBack = { navController.popBackStack() },
                                onOpen = { key ->
                                    val encoded = java.net.URLEncoder.encode(key, "UTF-8")
                                    navController.navigate("channelSession/$encoded")
                                },
                            )
                        }
                        composable(
                            route = "channelSession/{key}",
                            arguments = listOf(navArgument("key") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val raw = backStackEntry.arguments?.getString("key").orEmpty()
                            val key = java.net.URLDecoder.decode(raw, "UTF-8")
                            ChannelSessionViewScreen(
                                sessionKey = key,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("android")  { AndroidScreen(onBack = { navController.popBackStack() }) }
                        composable(
                            "pluginTile/{pluginId}/{toolName}",
                            arguments = listOf(
                                navArgument("pluginId") { type = NavType.StringType },
                                navArgument("toolName") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val p = URLDecoder.decode(entry.arguments?.getString("pluginId").orEmpty(), Charsets.UTF_8.name())
                            val t = URLDecoder.decode(entry.arguments?.getString("toolName").orEmpty(), Charsets.UTF_8.name())
                            PluginTileScreen(pluginId = p, toolName = t,
                                onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    private fun consumeIntentExtras(i: android.content.Intent?) {
        val seed = i?.getStringExtra("companionSeed")
        if (!seed.isNullOrBlank()) PendingCompanionSeed.set(seed)
        // Surface a navigation request to the Compose tree (LaunchedEffect
        // observer above). Drop unknown routes silently.
        val nav = i?.getStringExtra("nav")
        if (!nav.isNullOrBlank() && nav in KNOWN_NAV_ROUTES) {
            pendingNavRequest.value = nav
        }
    }

    /** Forge AutoPhone defines this permission; bind only after the user grants it. */
    private fun ensureAutoPhoneControlPermission() {
        when {
            ContextCompat.checkSelfPermission(this, AUTO_PHONE_CONTROL) == PackageManager.PERMISSION_GRANTED ->
                autoPhoneConnection.connect()
            else ->
                requestAutoPhoneControl.launch(AUTO_PHONE_CONTROL)
        }
    }

    /** POST_NOTIFICATIONS is a runtime permission on API 33+. Request it on first launch. */
    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Request all dangerous permissions that the tool providers need.
     * Only requests permissions that haven't been granted yet — the system
     * won't show a dialog for already-granted permissions.
     *
     * Grouped by feature so the user understands what each group is for:
     *   - Contacts / Calendar / SMS / Call log — personal data tools
     *   - Location — location_current / location_address
     *   - Bluetooth — bluetooth_paired_devices / bluetooth_connected_devices
     *   - Storage / Media — storage tools on API 33+
     */
    private fun ensureDangerousPermissions() {
        val needed = mutableListOf<String>()

        fun needsGrant(vararg perms: String) {
            perms.forEach { p ->
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed += p
                }
            }
        }

        // Contacts
        needsGrant(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
        )
        // Calendar
        needsGrant(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
        // SMS
        needsGrant(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS,
        )
        // Call log + dialer
        needsGrant(
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.CALL_PHONE,
        )
        // Location
        needsGrant(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        // Bluetooth (API 31+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            needsGrant(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
            )
        }
        // Media / storage (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            needsGrant(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            needsGrant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (needed.isNotEmpty()) {
            requestDangerousPermissions.launch(needed.toTypedArray())
        }
    }

    companion object {
        private const val AUTO_PHONE_CONTROL = "com.forge.autophone.permission.CONTROL"

        // Whitelist of routes that may be requested via a notification's
        // `nav` extra. Anything else is ignored to avoid pushing an unknown
        // destination onto the back stack.
        private val KNOWN_NAV_ROUTES = setOf(
            "chat", "companion", "external", "settings", "workspace", "browser",
            "hub", "channels", "channelSessions", "alarms", "cron",
            "memory", "agents", "skills", "snapshots", "mcp", "tools", "plugins",
            "status", "diagnostics", "doctor", "server", "android", "cost",
            "projects", "persona", "companionCheckIns", "companionMemory",
            "companionConversations", "conversations", "modelRouting",
            "toolOverrides", "backup",
        )
    }
}