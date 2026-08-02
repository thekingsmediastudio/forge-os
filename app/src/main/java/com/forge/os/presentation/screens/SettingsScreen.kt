package com.forge.os.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.KeyStatus
import com.forge.os.domain.security.ProviderSchema
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.theme.ThemeMode
import kotlinx.coroutines.delay

/**
 * Modern Settings screen — Quiet Power design.
 * Clean card-based layout with consistent typography and palette colors.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToModelRouting: () -> Unit = {},
    onNavigateToOverrides: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToPersonality: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val keyStatuses by viewModel.keyStatuses.collectAsState()
    val customStatuses by viewModel.customStatuses.collectAsState()
    val namedSecretStatuses by viewModel.namedSecretStatuses.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val compactModeEnabled by viewModel.compactModeEnabled.collectAsState()
    val costThresholdUsd by viewModel.costThresholdUsd.collectAsState()
    val remotePythonWorkerUrl by viewModel.remotePythonWorkerUrl.collectAsState()
    val remotePythonWorkerAuthToken by viewModel.remotePythonWorkerAuthToken.collectAsState()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()
    val prefetchEnabled by viewModel.prefetchEnabled.collectAsState()
    val prefetchAllowUnsafe by viewModel.prefetchAllowUnsafe.collectAsState()
    val reflectionEnabled by viewModel.reflectionEnabled.collectAsState()
    val memoryRagEnabled by viewModel.memoryRagEnabled.collectAsState()
    val visionEnabled by viewModel.visionEnabled.collectAsState()
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsState()
    val backupLoading by viewModel.backupLoading.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddSecretDialog by remember { mutableStateOf(false) }
    
    // Tutorial state
    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
    val tutorialManager = tutorialVm.tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    
    // Check if tutorial should be shown
    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowTutorial(com.forge.os.domain.tutorial.TutorialType.SETTINGS)) {
            kotlinx.coroutines.delay(500)
            showTutorial = true
        }
    }

    LaunchedEffect(saveMessage) {
        if (saveMessage != null) { delay(3000); viewModel.clearSaveMessage() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModernBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SimpleHeader(
                title = "Settings",
                subtitle = "Keys stored in Android Keystore",
                onBackClick = onNavigateBack
            ) {
                IconButton(
                    onClick = onNavigateToDiagnostics,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.BugReport,
                        "Diagnostics",
                        tint = ModernTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Save message toast
            AnimatedVisibility(visible = saveMessage != null) {
                saveMessage?.let {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        color = forgePalette.success.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            it,
                            color = forgePalette.success,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Appearance ───────────────────────────────────────────
                item { SectionHeader(title = "APPEARANCE") }
                item {
                    Box(modifier = Modifier.then(com.forge.os.presentation.components.spotlightTarget("settings_appearance"))) {
                        AppearanceCard(
                            selected = themeMode,
                            onSelect = { viewModel.setThemeMode(it) },
                            hapticEnabled = hapticFeedbackEnabled,
                            onHapticToggle = { viewModel.setHapticFeedbackEnabled(it) }
                        )
                    }
                }

                // ── Model ────────────────────────────────────────────────
                item { SectionHeader(title = "MODEL") }
                item {
                    CompactModeCard(
                        enabled = compactModeEnabled,
                        onToggle = { viewModel.setCompactModeEnabled(it) }
                    )
                }

                // ── Built-in Providers ───────────────────────────────────
                item { SectionHeader(title = "BUILT-IN PROVIDERS") }
                item {
                    Box(modifier = Modifier.then(com.forge.os.presentation.components.spotlightTarget("settings_api_keys"))) {
                        Column {
                            keyStatuses.forEach { status ->
                                ApiKeyCard(
                                    status = status,
                                    onSave = { key -> viewModel.saveKey(status.provider, key) },
                                    onDelete = { viewModel.deleteKey(status.provider) }
                                )
                            }
                        }
                    }
                }

                // ── Custom Endpoints ─────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = "CUSTOM ENDPOINTS", modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Outlined.Add, null, tint = ModernAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", color = ModernAccent, fontSize = 13.sp)
                        }
                    }
                }
                if (customStatuses.isEmpty()) {
                    item {
                        Text(
                            "None yet. Use Add to wire any OpenAI- or Anthropic-compatible URL.",
                            color = forgePalette.textDim,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                items(customStatuses) { cs ->
                    CustomEndpointCard(
                        status = cs,
                        onSetKey = { k -> viewModel.setCustomKey(cs.endpoint.id, k) },
                        onDelete = { viewModel.deleteCustomEndpoint(cs.endpoint.id) }
                    )
                }

                // ── Custom API Keys ──────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = "CUSTOM API KEYS", modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAddSecretDialog = true }) {
                            Icon(Icons.Outlined.Add, null, tint = ModernAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", color = ModernAccent, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Text(
                        "Register an API key by name. The agent references it by name only — the raw value never enters the model.",
                        color = forgePalette.textDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                if (namedSecretStatuses.isEmpty()) {
                    item {
                        Text("None yet.", color = forgePalette.textDim, fontSize = 12.sp)
                    }
                }
                items(namedSecretStatuses) { ns ->
                    NamedSecretCard(
                        status = ns,
                        onDelete = { viewModel.deleteNamedSecret(ns.secret.name) },
                    )
                }

                // ── Advanced Execution ───────────────────────────────────
                item { SectionHeader(title = "ADVANCED EXECUTION") }
                item {
                    AdvancedExecutionCard(
                        costThreshold = costThresholdUsd,
                        onSetCostThreshold = { viewModel.setCostThresholdUsd(it) },
                        remoteUrl = remotePythonWorkerUrl,
                        remoteToken = remotePythonWorkerAuthToken,
                        onSetHybrid = { url, token -> viewModel.setHybridExecution(url, token) }
                    )
                }

                // ── Predictive Prefetch ──────────────────────────────────
                item { SectionHeader(title = "PREDICTIVE PREFETCH") }
                item {
                    PredictivePrefetchCard(
                        enabled = prefetchEnabled,
                        onToggleEnabled = { viewModel.setPrefetchEnabled(it) },
                        allowUnsafe = prefetchAllowUnsafe,
                        onToggleAllowUnsafe = { viewModel.setPrefetchAllowUnsafe(it) }
                    )
                }

                // ── Intelligence Upgrades ────────────────────────────────
                item { SectionHeader(title = "INTELLIGENCE UPGRADES") }
                item {
                    IntelligenceUpgradesCard(
                        reflection = reflectionEnabled,
                        onReflectionToggle = { viewModel.setReflectionEnabled(it) },
                        memoryRag = memoryRagEnabled,
                        onMemoryRagToggle = { viewModel.setMemoryRagEnabled(it) },
                        vision = visionEnabled,
                        onVisionToggle = { viewModel.setVisionEnabled(it) },
                        reasoning = reasoningEnabled,
                        onReasoningToggle = { viewModel.setReasoningEnabled(it) }
                    )
                }

                // ── Backup ───────────────────────────────────────────────
                item { SectionHeader(title = "BACKUP") }
                item {
                    BackupCard(
                        loading = backupLoading,
                        onBackup = {
                            viewModel.performBackup { file ->
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Save Forge Backup"))
                            }
                        }
                    )
                }

                // ── Ollama Note ──────────────────────────────────────────
                item {
                    Surface(
                        color = ModernSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, forgePalette.borderSoft)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "For local Ollama, the \"key\" field is the host URL, e.g. http://192.168.1.x:11434/v1/",
                                color = ModernTextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // ── Capability Padlocks ──────────────────────────────────
                item { CapabilityPadlocksCard() }

                // ── Wishlist Features ────────────────────────────────────
                item { SectionHeader(title = "FEATURES") }
                item { WishlistFeaturesCard() }

                // ── Memory Channels ──────────────────────────────────────
                item { SectionHeader(title = "MEMORY CHANNELS") }
                item {
                    val channelVm: com.forge.os.presentation.screens.channels.ChannelViewModel = hiltViewModel()
                    val channelsEnabled by channelVm.channelsEnabled.collectAsState()
                    
                    Box(modifier = Modifier.then(com.forge.os.presentation.components.spotlightTarget("settings_channels"))) {
                        SettingsToggleRow(
                            icon = Icons.Outlined.Folder,
                            title = "Enable channels",
                            subtitle = "Separate memory by context (Work, Personal, etc.)",
                            checked = channelsEnabled,
                            onCheckedChange = { channelVm.setChannelsEnabled(it) }
                        )
                    }
                }
                item {
                    val channelVm: com.forge.os.presentation.screens.channels.ChannelViewModel = hiltViewModel()
                    val channelsEnabled by channelVm.channelsEnabled.collectAsState()
                    
                    if (channelsEnabled) {
                        SettingsNavRow(
                            icon = Icons.Outlined.ManageAccounts,
                            title = "Manage channels",
                            subtitle = "Create, edit, and organize your channels",
                            onClick = onNavigateToMemories
                        )
                    }
                }

                // ── Help & Tutorials ─────────────────────────────────────
                item { SectionHeader(title = "HELP & TUTORIALS") }
                item {
                    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
                    SettingsNavRow(
                        icon = Icons.Outlined.School,
                        title = "Replay tutorials",
                        subtitle = "Show interface guides again",
                        onClick = {
                            tutorialVm.tutorialManager.resetAllTutorials()
                        }
                    )
                }

                // ── Routing & Security ───────────────────────────────────
                item { SectionHeader(title = "ROUTING & SECURITY") }
                item {
                    SettingsNavRow(
                        icon = Icons.Outlined.AltRoute,
                        title = "Model routing",
                        subtitle = "Edit fallback chain & background-caller toggles",
                        onClick = onNavigateToModelRouting
                    )
                }
                item {
                    SettingsNavRow(
                        icon = Icons.Outlined.Lock,
                        title = "Advanced overrides",
                        subtitle = "Per-tool blocked hosts/extensions/configs",
                        onClick = onNavigateToOverrides
                    )
                }
                item {
                    SettingsNavRow(
                        icon = Icons.Outlined.Person,
                        title = "Personality",
                        subtitle = "Customize agent name, traits, communication style",
                        onClick = onNavigateToPersonality
                    )
                }
                item {
                    SettingsNavRow(
                        icon = Icons.Outlined.Backup,
                        title = "Backup & Restore",
                        subtitle = "Create system snapshot or restore from backup",
                        onClick = onNavigateToBackup
                    )
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
        
        // Tutorial Overlay
        if (showTutorial) {
            val tutorialSteps = listOf(
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Settings",
                    description = "Configure Forge OS to work the way you want. All settings are stored securely on your device.",
                    targetKey = null,
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "API Keys",
                    description = "Add your AI provider API keys here. They're stored encrypted in Android Keystore.",
                    targetKey = "settings_api_keys",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Appearance",
                    description = "Customize the look and feel with themes and display options.",
                    targetKey = "settings_appearance",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Memory Channels",
                    description = "Enable channels to separate AI memory by context (Work, Personal, etc.)",
                    targetKey = "settings_channels",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
                )
            )
            
            com.forge.os.presentation.components.CoachMarkOverlay(
                steps = tutorialSteps,
                currentStep = tutorialStep,
                onNext = { tutorialStep++ },
                onSkip = {
                    showTutorial = false
                    tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.SETTINGS)
                },
                onDone = {
                    showTutorial = false
                    tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.SETTINGS)
                }
            )
        }
    }

    if (showAddDialog) {
        AddCustomEndpointDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, schema, model, key ->
                viewModel.addCustomEndpoint(name, url, schema, model, key)
                showAddDialog = false
            }
        )
    }

    if (showAddSecretDialog) {
        AddNamedSecretDialog(
            onDismiss = { showAddSecretDialog = false },
            onConfirm = { name, desc, style, header, query, value ->
                viewModel.saveNamedSecret(name, desc, style, header, query, value)
                showAddSecretDialog = false
            },
        )
    }
}

// ── Backup Card ─────────────────────────────────────────────────────────────

@Composable
private fun BackupCard(
    loading: Boolean,
    onBackup: () -> Unit
) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(ModernAccent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = ModernAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        "System Backup",
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Full ZIP archive of workspace, memory, and settings",
                        color = ModernTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            ModernButton(
                text = if (loading) "Creating Backup..." else "Generate Full Backup",
                onClick = onBackup,
                enabled = !loading,
                icon = Icons.Outlined.Backup,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── Named Secret Card ───────────────────────────────────────────────────────

@Composable
private fun NamedSecretCard(
    status: NamedSecretStatus,
    onDelete: () -> Unit,
) {
    val s = status.secret
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.name,
                        color = ModernAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "[${s.authStyle}]",
                        color = ModernTextSecondary,
                        fontSize = 11.sp
                    )
                }
                StatusBadge(
                    status = if (status.hasValue) "Stored" else "No value",
                    color = if (status.hasValue) forgePalette.success else forgePalette.danger
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        "Delete",
                        tint = forgePalette.danger,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (s.description.isNotBlank()) {
                Text(
                    s.description,
                    color = ModernTextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            val attach = when (s.authStyle) {
                "bearer" -> "Authorization: Bearer ..."
                "header" -> "${s.headerName}: ..."
                "query"  -> "?${s.queryParam}=..."
                else     -> s.authStyle
            }
            Text(attach, color = ModernTextSecondary, fontSize = 11.sp)
        }
    }
}

// ── Add Named Secret Dialog ─────────────────────────────────────────────────

@Composable
private fun AddNamedSecretDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, authStyle: String,
                headerName: String, queryParam: String, value: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("bearer") }
    var headerName by remember { mutableStateOf("Authorization") }
    var queryParam by remember { mutableStateOf("key") }
    var value by remember { mutableStateOf("") }
    var showValue by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ModernSurface,
        titleContentColor = ModernTextPrimary,
        textContentColor = ModernTextPrimary,
        title = { Text("Add custom API key", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTextField(value = name, onValueChange = { name = it }, label = "Name (e.g. github_pat)")
                SettingsTextField(value = description, onValueChange = { description = it }, label = "What is it for?")
                Text("How to attach it:", color = ModernTextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("bearer", "header", "query").forEach { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { style = opt }
                        ) {
                            RadioButton(
                                selected = style == opt,
                                onClick = { style = opt },
                                colors = RadioButtonDefaults.colors(selectedColor = ModernAccent)
                            )
                            Text(opt, fontSize = 13.sp, color = ModernTextPrimary)
                        }
                    }
                }
                if (style == "header") {
                    SettingsTextField(value = headerName, onValueChange = { headerName = it }, label = "Header name")
                }
                if (style == "query") {
                    SettingsTextField(value = queryParam, onValueChange = { queryParam = it }, label = "Query parameter name")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Secret value (stored encrypted)") },
                    singleLine = true,
                    visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showValue = !showValue }) {
                            Icon(
                                if (showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                "Toggle visibility",
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = settingsTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description, style, headerName, queryParam, value) },
                enabled = name.isNotBlank() && value.isNotBlank(),
            ) { Text("Save", color = ModernAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ModernTextSecondary) } },
    )
}

// ── Compact Mode Card ───────────────────────────────────────────────────────

@Composable
private fun CompactModeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsToggleRow(
                title = "Compact Mode",
                subtitle = if (enabled) "ON — shorter replies, lower token cost"
                           else "OFF — full replies, normal model routing",
                subtitleColor = if (enabled) forgePalette.success else ModernTextSecondary,
                checked = enabled,
                onCheckedChange = onToggle
            )

            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

            Text("Turn ON if you:", color = forgePalette.success, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            listOf(
                "Want quick, to-the-point answers",
                "Are watching your API spend",
                "Use a provider with a small free quota",
                "Don't need long explanations or code blocks"
            ).forEach { line ->
                SettingsBullet(text = line, color = ModernTextSecondary)
            }

            Spacer(Modifier.height(2.dp))

            Text("Leave OFF if you:", color = ModernTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            listOf(
                "Want thorough, detailed responses",
                "Do complex coding or writing tasks",
                "Have an unlimited or high-quota API key",
                "Want the agent to use your chosen model fully"
            ).forEach { line ->
                SettingsBullet(text = line, color = forgePalette.textDim)
            }

            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

            Text(
                "When on: replies capped at 512 tokens · last 8 messages sent · routes to Groq by default.",
                color = forgePalette.textDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// ── API Key Card ────────────────────────────────────────────────────────────

@Composable
private fun ApiKeyCard(
    status: KeyStatus,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var inputKey by remember(status.provider) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(!status.hasKey) }

    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        status.provider.displayName,
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        status.provider.baseUrl,
                        color = ModernTextSecondary,
                        fontSize = 11.sp
                    )
                }
                StatusBadge(
                    status = if (status.hasKey) "Set" else "Empty",
                    color = if (status.hasKey) forgePalette.success else forgePalette.textMuted
                )
                if (status.hasKey) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            "Delete key",
                            tint = ModernTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (status.hasKey && !editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        status.maskedKey,
                        color = ModernTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = true }) {
                        Text("Change", color = ModernAccent, fontSize = 13.sp)
                    }
                }
            }

            if (editing || !status.hasKey) {
                OutlinedTextField(
                    value = inputKey,
                    onValueChange = { inputKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (status.provider == ApiKeyProvider.OLLAMA) "http://host:11434/v1/" else "sk-...",
                            color = forgePalette.textDim,
                            fontSize = 13.sp
                        )
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                null,
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = settingsTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.hasKey) {
                        OutlinedButton(
                            onClick = { editing = false; inputKey = "" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernTextSecondary)
                        ) { Text("Cancel", fontSize = 13.sp) }
                    }
                    Button(
                        onClick = { onSave(inputKey); editing = false; inputKey = "" },
                        enabled = inputKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ModernAccent)
                    ) {
                        Text("Save Key", fontSize = 13.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// ── Custom Endpoint Card ────────────────────────────────────────────────────

@Composable
private fun CustomEndpointCard(
    status: CustomEndpointStatus,
    onSetKey: (String) -> Unit,
    onDelete: () -> Unit
) {
    var inputKey by remember(status.endpoint.id) { mutableStateOf("") }
    var editing by remember { mutableStateOf(!status.hasKey) }
    var showKey by remember { mutableStateOf(false) }

    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        status.endpoint.name,
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${status.endpoint.baseUrl} · ${status.endpoint.schema.name} · ${status.endpoint.defaultModel}",
                        color = ModernTextSecondary,
                        fontSize = 11.sp
                    )
                }
                StatusBadge(
                    status = if (status.hasKey) "Set" else "Empty",
                    color = if (status.hasKey) forgePalette.success else forgePalette.textMuted
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        "Delete endpoint",
                        tint = ModernTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (status.hasKey && !editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(status.maskedKey, color = ModernTextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = true }) {
                        Text("Change", color = ModernAccent, fontSize = 13.sp)
                    }
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = inputKey,
                    onValueChange = { inputKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("API key", color = forgePalette.textDim, fontSize = 13.sp) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                null,
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = settingsTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = { onSetKey(inputKey); editing = false; inputKey = "" },
                    enabled = inputKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ModernAccent)
                ) {
                    Text("Save Key", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}

// ── Appearance Card ─────────────────────────────────────────────────────────

@Composable
private fun AppearanceCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    hapticEnabled: Boolean,
    onHapticToggle: (Boolean) -> Unit
) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Theme",
                color = ModernTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Applies immediately across the app",
                color = ModernTextSecondary,
                fontSize = 12.sp
            )
            ThemeMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = ModernAccent,
                            unselectedColor = forgePalette.textDim
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(mode.displayName, color = ModernTextPrimary, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

            SettingsToggleRow(
                title = "Haptic Feedback",
                subtitle = "Tactile response when agent is active",
                checked = hapticEnabled,
                onCheckedChange = onHapticToggle
            )
        }
    }
}

// ── Add Custom Endpoint Dialog ──────────────────────────────────────────────

@Composable
private fun AddCustomEndpointDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, schema: ProviderSchema, model: String, key: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var schema by remember { mutableStateOf(ProviderSchema.OPENAI) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ModernSurface,
        titleContentColor = ModernTextPrimary,
        textContentColor = ModernTextPrimary,
        title = { Text("Add custom endpoint", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTextField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Local LM Studio")
                SettingsTextField(value = url, onValueChange = { url = it }, label = "Base URL", placeholder = "https://host/v1/")
                SettingsTextField(value = model, onValueChange = { model = it }, label = "Default model", placeholder = "model-id")
                SettingsTextField(value = key, onValueChange = { key = it }, label = "API key", placeholder = "sk-...", isSecret = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Schema:", color = ModernTextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = schema == ProviderSchema.OPENAI,
                        onClick = { schema = ProviderSchema.OPENAI },
                        label = { Text("OpenAI", fontSize = 12.sp) }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = schema == ProviderSchema.ANTHROPIC,
                        onClick = { schema = ProviderSchema.ANTHROPIC },
                        label = { Text("Anthropic", fontSize = 12.sp) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, schema, model, key) },
                enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank()
            ) { Text("Add", color = ModernAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ModernTextSecondary) }
        }
    )
}

// ── Capability Padlocks ─────────────────────────────────────────────────────

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ControlPlaneEntryPoint {
    fun controlPlane(): com.forge.os.domain.control.AgentControlPlane
}

@Composable
private fun CapabilityPadlocksCard() {
    val ctx = LocalContext.current
    val plane = remember {
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(ctx.applicationContext, ControlPlaneEntryPoint::class.java)
            .controlPlane()
    }
    val states by plane.states.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = ModernAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Capability Padlocks",
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "Hide" else "Show",
                        color = ModernTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Text(
                "Per-tool consent gates. Toggle on to grant the agent permission; toggle off to revoke.",
                color = ModernTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            if (expanded) {
                plane.capabilities.groupBy { it.category }.forEach { (cat, caps) ->
                    Text(
                        cat.uppercase(),
                        color = ModernAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    caps.forEach { cap ->
                        val on = states[cap.id]?.enabled ?: cap.defaultEnabled
                        SettingsToggleRow(
                            title = cap.title.ifBlank { cap.id },
                            subtitle = cap.description,
                            checked = on,
                            onCheckedChange = { wanted -> plane.setByUser(cap.id, wanted) }
                        )
                    }
                }
            }
        }
    }
}

// ── Predictive Prefetch Card ────────────────────────────────────────────────

@Composable
private fun PredictivePrefetchCard(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    allowUnsafe: Boolean,
    onToggleAllowUnsafe: (Boolean) -> Unit,
) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsToggleRow(
                title = "Predictive Prefetch",
                subtitle = if (enabled) "ON — agent anticipates your next request"
                           else "OFF — no background prediction",
                subtitleColor = if (enabled) forgePalette.success else ModernTextSecondary,
                checked = enabled,
                onCheckedChange = onToggleEnabled
            )

            Text(
                "When enabled, Forge analyses your recent activity and proactively executes predicted tool calls in the background.",
                color = ModernTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            AnimatedVisibility(visible = enabled) {
                Column {
                    HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
                    Spacer(Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Allow Unsafe Tools",
                        subtitle = if (allowUnsafe)
                            "Prefetch may run write/mutating tools"
                        else
                            "Safe mode — only read-only tools",
                        subtitleColor = if (allowUnsafe) forgePalette.warning else ModernTextSecondary,
                        checked = allowUnsafe,
                        onCheckedChange = onToggleAllowUnsafe
                    )
                }
            }
        }
    }
}

// ── Intelligence Upgrades Card ──────────────────────────────────────────────

@Composable
private fun IntelligenceUpgradesCard(
    reflection: Boolean,
    onReflectionToggle: (Boolean) -> Unit,
    memoryRag: Boolean,
    onMemoryRagToggle: (Boolean) -> Unit,
    vision: Boolean,
    onVisionToggle: (Boolean) -> Unit,
    reasoning: Boolean,
    onReasoningToggle: (Boolean) -> Unit,
) {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsToggleRow(
                title = "Autonomous Learning (Reflection)",
                subtitle = "Agent analyzes errors and circular loops to improve",
                checked = reflection,
                onCheckedChange = onReflectionToggle
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            SettingsToggleRow(
                title = "Long-term Memory (RAG)",
                subtitle = "Prioritizes past project knowledge in every prompt",
                checked = memoryRag,
                onCheckedChange = onMemoryRagToggle
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            SettingsToggleRow(
                title = "Vision Processing",
                subtitle = "Allows agent to see and reason about screenshots/images",
                checked = vision,
                onCheckedChange = onVisionToggle
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            SettingsToggleRow(
                title = "Advanced Reasoning",
                subtitle = "Uses specialized deep-thought models for complex tasks",
                checked = reasoning,
                onCheckedChange = onReasoningToggle
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "These features may increase token usage and response latency.",
                color = forgePalette.textDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// ── Advanced Execution Card ─────────────────────────────────────────────────

@Composable
private fun AdvancedExecutionCard(
    costThreshold: Double,
    onSetCostThreshold: (Double) -> Unit,
    remoteUrl: String,
    remoteToken: String,
    onSetHybrid: (String, String) -> Unit,
) {
    var inputThreshold by remember(costThreshold) { mutableStateOf(costThreshold.toString()) }
    var inputUrl by remember(remoteUrl) { mutableStateOf(remoteUrl) }
    var inputToken by remember(remoteToken) { mutableStateOf(remoteToken) }
    var showToken by remember { mutableStateOf(false) }

    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Cost Threshold
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Budget Gate (USD)",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Agent pauses for approval if estimated run cost exceeds this. 0.0 = disabled.",
                    color = ModernTextSecondary,
                    fontSize = 12.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputThreshold,
                        onValueChange = { inputThreshold = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("0.05", color = forgePalette.textDim) },
                        colors = settingsTextFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = { onSetCostThreshold(inputThreshold.replace(',', '.').toDoubleOrNull() ?: 0.0) },
                        colors = ButtonDefaults.buttonColors(containerColor = ModernAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Set", fontSize = 13.sp, color = Color.White)
                    }
                }
            }

            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

            // Hybrid Execution
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Remote Python Worker (GPU)",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Auto-routes heavy ML scripts to this endpoint instead of on-device Chaquopy.",
                    color = ModernTextSecondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Worker URL") },
                    colors = settingsTextFieldColors(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = inputToken,
                    onValueChange = { inputToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auth Token (Optional)") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                null,
                                tint = ModernTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = settingsTextFieldColors(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = { onSetHybrid(inputUrl, inputToken) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ModernAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save Hybrid Settings", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}

// ── Wishlist Features Card ──────────────────────────────────────────────────

@Composable
private fun WishlistFeaturesCard() {
    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureRow(
                icon = Icons.Outlined.Mic,
                title = "Voice Input",
                subtitle = "Hands-free control via speech"
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            FeatureRow(
                icon = Icons.Outlined.Sync,
                title = "Multi-Device Sync",
                subtitle = "Sync projects & memory across devices"
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            FeatureRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "AI Code Review",
                subtitle = "Automated code quality & security checks"
            )
            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
            FeatureRow(
                icon = Icons.Outlined.MonitorHeart,
                title = "Project Health Dashboard",
                subtitle = "Monitor tests, builds, & code quality"
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "All features are accessible via agent tools.",
                color = forgePalette.textDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(ModernAccent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = ModernAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = ModernTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ModernTextSecondary, fontSize = 11.sp)
        }
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = "Available",
            tint = forgePalette.success,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ── Settings Nav Row ────────────────────────────────────────────────────────

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, forgePalette.borderSoft)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(ModernAccent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = ModernAccent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = ModernTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = ModernTextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = forgePalette.textDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Shared Settings Components ──────────────────────────────────────────────

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    subtitleColor: Color = ModernTextSecondary,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ModernTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = subtitleColor, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(newValue)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ModernAccent,
                uncheckedThumbColor = forgePalette.textDim,
                uncheckedTrackColor = forgePalette.borderSoft
            )
        )
    }
}

@Composable
private fun SettingsBullet(text: String, color: Color) {
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(y = 6.dp)
                .background(color, CircleShape)
        )
        Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isSecret: Boolean = false
) {
    Column {
        Text(label, color = ModernTextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = forgePalette.textDim, fontSize = 13.sp) },
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            colors = settingsTextFieldColors(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ModernAccent,
    unfocusedBorderColor = forgePalette.borderSoft,
    focusedTextColor = ModernTextPrimary,
    unfocusedTextColor = ModernTextPrimary,
    cursorColor = ModernAccent,
    focusedLabelColor = ModernAccent,
    unfocusedLabelColor = ModernTextSecondary
)
