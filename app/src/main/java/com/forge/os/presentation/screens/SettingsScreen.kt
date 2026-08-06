package com.forge.os.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.text.font.FontFamily
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
import com.forge.os.domain.permissions.PermissionGroup
import com.forge.os.domain.permissions.PermissionManager
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
    val hotwordEnabled by viewModel.hotwordEnabled.collectAsState()
    val prefetchEnabled by viewModel.prefetchEnabled.collectAsState()
    val prefetchAllowUnsafe by viewModel.prefetchAllowUnsafe.collectAsState()
    val reflectionEnabled by viewModel.reflectionEnabled.collectAsState()
    val memoryRagEnabled by viewModel.memoryRagEnabled.collectAsState()
    val visionEnabled by viewModel.visionEnabled.collectAsState()
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsState()
    val backupLoading by viewModel.backupLoading.collectAsState()
    val apiServerRunning by viewModel.apiServerRunning.collectAsState()
    val apiServerPort by viewModel.apiServerPort.collectAsState()
    val apiServerKey by viewModel.apiServerKey.collectAsState()
    val context = LocalContext.current

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

            // ── Search bar ──────────────────────────────────────────────
            var settingsSearchQuery by remember { mutableStateOf("") }
            ForgeSearchBar(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it },
                placeholder = "Search settings…",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            // ── Grid home + section detail navigation ───────────────────
            var currentSection by remember { mutableStateOf<String?>(null) }
            val sections = settingsSections(
                providerBadge = "${keyStatuses.count { it.hasKey }}/${keyStatuses.size}",
                endpointsBadge = "${customStatuses.size}",
                secretsBadge = "${namedSecretStatuses.size}"
            )

            if (currentSection == null) {
                SettingsHomeGrid(
                    sections = sections,
                    searchQuery = settingsSearchQuery,
                    onOpenSection = { currentSection = it }
                )
            } else {
                val section = sections.firstOrNull { it.key == currentSection }
                if (section == null) {
                    currentSection = null
                } else {
                    SettingsSectionDetail(
                        section = section,
                        onBack = { currentSection = null }
                    ) {
                        SettingsSectionBody(
                            sectionKey = section.key,
                            viewModel = viewModel,
                            onNavigateToMemories = onNavigateToMemories,
                            onNavigateToModelRouting = onNavigateToModelRouting,
                            onNavigateToOverrides = onNavigateToOverrides,
                            onNavigateToPersonality = onNavigateToPersonality,
                            onNavigateToBackup = onNavigateToBackup
                        )
                    }
                }
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
    onHapticToggle: (Boolean) -> Unit,
    hotwordEnabled: Boolean,
    onHotwordToggle: (Boolean) -> Unit
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

            HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

            SettingsToggleRow(
                title = "\"Hello Forge\" wake word",
                subtitle = "Always-on mic listening. Off saves battery & frees your mic.",
                checked = hotwordEnabled,
                onCheckedChange = onHotwordToggle
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

// ── Permissions Card ────────────────────────────────────────────────────────

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    val permissionManager = remember { PermissionManager(context) }
    var permissionGroups by remember { mutableStateOf(permissionManager.getPermissionGroups()) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Refresh permission states after request
        permissionGroups = permissionManager.getPermissionGroups()
    }

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
                        Icons.Outlined.Security,
                        contentDescription = null,
                        tint = ModernAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "App Permissions",
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                "Grant permissions to enable agent tools. Tap a category to request.",
                color = ModernTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            permissionGroups.forEach { group ->
                PermissionGroupRow(
                    group = group,
                    isExpanded = expandedGroup == group.id,
                    onToggleExpand = {
                        expandedGroup = if (expandedGroup == group.id) null else group.id
                    },
                    onRequest = {
                        val toRequest = permissionManager.getPermissionsToRequest(group)
                        if (toRequest.isNotEmpty()) {
                            permissionLauncher.launch(toRequest)
                        }
                    }
                )
                if (group.id != permissionGroups.last().id) {
                    HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun PermissionGroupRow(
    group: PermissionGroup,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRequest: () -> Unit
) {
    val allGranted = group.isGranted

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (allGranted) forgePalette.success.copy(alpha = 0.12f)
                        else ModernAccent.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(group.icon, fontSize = 18.sp)
            }

            // Text
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    color = ModernTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    group.description,
                    color = ModernTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            // Status
            if (allGranted) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Granted",
                    tint = forgePalette.success,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                TextButton(onClick = onRequest) {
                    Text("Grant", color = ModernAccent, fontSize = 12.sp)
                }
            }
        }

        // Expanded details
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    "Permissions in this group:",
                    color = ModernTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                group.permissions.forEach { perm ->
                    val permName = perm.substringAfterLast(".")
                    Text(
                        "• $permName",
                        color = forgePalette.textDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (!allGranted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onRequest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernAccent)
                    ) {
                        Text("Grant ${group.name} Permissions", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── API Server Card ─────────────────────────────────────────────────────────

@Composable
private fun ApiServerCard(
    running: Boolean,
    port: Int,
    apiKey: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRegenerateToken: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }

    ModernCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Icons.Outlined.Api,
                        contentDescription = null,
                        tint = ModernAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Python SDK Server",
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                StatusBadge(
                    status = if (running) "Running" else "Stopped",
                    color = if (running) forgePalette.success else forgePalette.textMuted
                )
            }

            Text(
                "Allow external Python scripts to call Forge tools via local HTTP API.",
                color = ModernTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            if (running) {
                HorizontalDivider(color = forgePalette.divider, thickness = 0.5.dp)

                // Connection info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Host", color = ModernTextSecondary, fontSize = 11.sp)
                        Text("127.0.0.1:$port", color = ModernTextPrimary, fontSize = 13.sp)
                    }
                }

                // API Key
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("API Key", color = ModernTextSecondary, fontSize = 11.sp)
                        Text(
                            if (showKey) apiKey else "••••••••••••••••",
                            color = ModernTextPrimary,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            "Toggle key visibility",
                            tint = ModernTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // SDK usage hint
                Surface(
                    color = ModernAccent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Python SDK Usage:",
                            color = ModernAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "from forge_sdk import ForgeClient\n" +
                            "client = ForgeClient(token=\"${apiKey.take(8)}...\")\n" +
                            "result = client.call_tool(\"file_list\", path=\".\")",
                            color = ModernTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRegenerateToken,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernTextSecondary)
                    ) {
                        Text("Rotate Key", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = forgePalette.danger)
                    ) {
                        Text("Stop Server", fontSize = 12.sp, color = Color.White)
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ModernAccent)
                ) {
                    Text("Start API Server", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}

// ── Settings grid home + section detail ─────────────────────────────────────

private data class SettingsSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val emoji: String,
    val group: String,
    val keywords: List<String>,
    val isAdvanced: Boolean = false,
    val badge: (() -> String?)? = null
)

private fun settingsSections(
    providerBadge: String,
    endpointsBadge: String,
    secretsBadge: String
): List<SettingsSection> = listOf(
    SettingsSection(
        key = "model",
        title = "Model",
        subtitle = "Compact mode & token budget",
        emoji = "🤖",
        group = "AI & Model",
        keywords = listOf("model", "compact", "token", "budget", "cost", "threshold")
    ),
    SettingsSection(
        key = "providers",
        title = "Providers",
        subtitle = "OpenAI, Anthropic, Groq, Ollama…",
        emoji = "🔑",
        group = "AI & Model",
        keywords = listOf("provider", "api", "key", "openai", "anthropic", "groq", "ollama", "gemini"),
        badge = { providerBadge }
    ),
    SettingsSection(
        key = "endpoints",
        title = "Custom Endpoints",
        subtitle = "Any OpenAI/Anthropic-compatible URL",
        emoji = "🔗",
        group = "AI & Model",
        keywords = listOf("custom", "endpoint", "url", "add", "compatible"),
        badge = { endpointsBadge }
    ),
    SettingsSection(
        key = "secrets",
        title = "Custom API Keys",
        subtitle = "Named keys the agent can reference",
        emoji = "🔐",
        group = "AI & Model",
        keywords = listOf("secret", "named", "api key", "token", "credential"),
        badge = { secretsBadge }
    ),
    SettingsSection(
        key = "intelligence",
        title = "Intelligence",
        subtitle = "Reflection, memory, vision, reasoning",
        emoji = "🧠",
        group = "AI & Model",
        keywords = listOf("intelligence", "reflection", "memory", "rag", "vision", "reasoning", "skill")
    ),
    SettingsSection(
        key = "advanced",
        title = "Advanced Execution",
        subtitle = "Hybrid GPU worker & cost threshold",
        emoji = "⚡",
        group = "AI & Model",
        keywords = listOf("advanced", "execution", "remote", "gpu", "worker", "python", "cost threshold"),
        isAdvanced = true
    ),
    SettingsSection(
        key = "prefetch",
        title = "Predictive Prefetch",
        subtitle = "Preload models & cache",
        emoji = "🚀",
        group = "AI & Model",
        keywords = listOf("prefetch", "predictive", "cache", "unsafe", "preload"),
        isAdvanced = true
    ),
    SettingsSection(
        key = "appearance",
        title = "Appearance",
        subtitle = "Theme, haptics, wake word",
        emoji = "🎨",
        group = "Personalization",
        keywords = listOf("appearance", "theme", "dark", "light", "haptic", "display", "hotword", "wake")
    ),
    SettingsSection(
        key = "channels",
        title = "Memory Channels",
        subtitle = "Separate memory by context",
        emoji = "🧵",
        group = "Personalization",
        keywords = listOf("memory", "channel", "context", "work", "personal")
    ),
    SettingsSection(
        key = "routing",
        title = "Routing & Security",
        subtitle = "Model routing, overrides, personality",
        emoji = "🛡️",
        group = "Personalization",
        keywords = listOf("routing", "security", "model routing", "override", "personality", "backup", "restore")
    ),
    SettingsSection(
        key = "padlocks",
        title = "Capability Padlocks",
        subtitle = "Restrict agent capabilities",
        emoji = "🔒",
        group = "Personalization",
        keywords = listOf("padlock", "capability", "lock", "security", "restrict"),
        isAdvanced = true
    ),
    SettingsSection(
        key = "backup",
        title = "Backup & Data",
        subtitle = "Export & restore your data",
        emoji = "💾",
        group = "System",
        keywords = listOf("backup", "export", "zip", "archive", "save", "restore")
    ),
    SettingsSection(
        key = "permissions",
        title = "Permissions",
        subtitle = "Manage granted access",
        emoji = "🔑",
        group = "System",
        keywords = listOf("permission", "access", "allow", "grant")
    ),
    SettingsSection(
        key = "apiserver",
        title = "API Server",
        subtitle = "Local HTTP SDK endpoint",
        emoji = "🖥️",
        group = "System",
        keywords = listOf("api", "server", "http", "sdk", "port", "token"),
        isAdvanced = true
    ),
    SettingsSection(
        key = "about",
        title = "About",
        subtitle = "Version & credits",
        emoji = "ℹ️",
        group = "System",
        keywords = listOf("about", "version", "forge", "build", "labs")
    ),
    SettingsSection(
        key = "help",
        title = "Help & Tutorials",
        subtitle = "Replay the interface guides",
        emoji = "📚",
        group = "Support",
        keywords = listOf("help", "tutorial", "guide", "replay", "learn")
    )
)

@Composable
private fun SettingsSectionTile(
    section: SettingsSection,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = forgePalette.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, forgePalette.borderSoft)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (section.isAdvanced) forgePalette.danger.copy(alpha = 0.10f)
                            else ModernAccent.copy(alpha = 0.10f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(section.emoji, fontSize = 18.sp)
                }
                Spacer(Modifier.weight(1f))
                section.badge?.invoke()?.let { badgeText ->
                    Surface(
                        color = forgePalette.surface2,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            badgeText,
                            color = forgePalette.textDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    section.title,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (section.isAdvanced) {
                    Spacer(Modifier.width(6.dp))
                    StatusBadge(status = "DEV", color = forgePalette.danger)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                section.subtitle,
                color = ModernTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title.uppercase(),
        color = ModernTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsHomeGrid(
    sections: List<SettingsSection>,
    searchQuery: String,
    onOpenSection: (String) -> Unit
) {
    val query = searchQuery.trim().lowercase()
    val filtered = if (query.isBlank()) {
        sections
    } else {
        sections.filter { section ->
            section.title.lowercase().contains(query) ||
                section.subtitle.lowercase().contains(query) ||
                section.group.lowercase().contains(query) ||
                section.keywords.any { it.lowercase().contains(query) }
        }
    }
    val groupOrder = listOf("AI & Model", "Personalization", "System", "Support")
    val grouped = filtered.groupBy { it.group }

    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No settings match",
                color = ModernTextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        groupOrder.forEach { groupName ->
            val itemsInGroup = grouped[groupName] ?: return@forEach
            item(span = { GridItemSpan(maxLineSpan) }) {
                SettingsGroupHeader(title = groupName)
            }
            gridItems(itemsInGroup, key = { it.key }) { section ->
                SettingsSectionTile(section = section, onClick = { onOpenSection(section.key) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionDetail(
    section: SettingsSection,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = ModernAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    section.title,
                    color = ModernTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { content() }
        }
    }
}

@Composable
private fun SettingsSectionBody(
    sectionKey: String,
    viewModel: SettingsViewModel,
    onNavigateToMemories: () -> Unit,
    onNavigateToModelRouting: () -> Unit,
    onNavigateToOverrides: () -> Unit,
    onNavigateToPersonality: () -> Unit,
    onNavigateToBackup: () -> Unit
) {
    val context = LocalContext.current
    val keyStatuses by viewModel.keyStatuses.collectAsState()
    val customStatuses by viewModel.customStatuses.collectAsState()
    val namedSecretStatuses by viewModel.namedSecretStatuses.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val compactModeEnabled by viewModel.compactModeEnabled.collectAsState()
    val costThresholdUsd by viewModel.costThresholdUsd.collectAsState()
    val remotePythonWorkerUrl by viewModel.remotePythonWorkerUrl.collectAsState()
    val remotePythonWorkerAuthToken by viewModel.remotePythonWorkerAuthToken.collectAsState()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()
    val hotwordEnabled by viewModel.hotwordEnabled.collectAsState()
    val prefetchEnabled by viewModel.prefetchEnabled.collectAsState()
    val prefetchAllowUnsafe by viewModel.prefetchAllowUnsafe.collectAsState()
    val reflectionEnabled by viewModel.reflectionEnabled.collectAsState()
    val memoryRagEnabled by viewModel.memoryRagEnabled.collectAsState()
    val visionEnabled by viewModel.visionEnabled.collectAsState()
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsState()
    val backupLoading by viewModel.backupLoading.collectAsState()
    val apiServerRunning by viewModel.apiServerRunning.collectAsState()
    val apiServerPort by viewModel.apiServerPort.collectAsState()
    val apiServerKey by viewModel.apiServerKey.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddSecretDialog by remember { mutableStateOf(false) }

    when (sectionKey) {
        "appearance" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.spotlightTarget("settings_appearance")) {
                    AppearanceCard(
                        selected = themeMode,
                        onSelect = { viewModel.setThemeMode(it) },
                        hapticEnabled = hapticFeedbackEnabled,
                        onHapticToggle = { viewModel.setHapticFeedbackEnabled(it) },
                        hotwordEnabled = hotwordEnabled,
                        onHotwordToggle = { enabled ->
                            viewModel.setHotwordEnabled(enabled)
                            // When enabling, make sure we can show the bubble over other apps.
                            if (enabled && !android.provider.Settings.canDrawOverlays(context)) {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }
            }
        }

        "model" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactModeCard(
                    enabled = compactModeEnabled,
                    onToggle = { viewModel.setCompactModeEnabled(it) }
                )
            }
        }

        "providers" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.spotlightTarget("settings_api_keys")) {
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
                // ── Ollama Note ──────────────────────────────────────────
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
        }

        "endpoints" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, null, tint = ModernAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = ModernAccent, fontSize = 13.sp)
                    }
                }
                if (customStatuses.isEmpty()) {
                    Text(
                        "None yet. Use Add to wire any OpenAI- or Anthropic-compatible URL.",
                        color = forgePalette.textDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                customStatuses.forEach { cs ->
                    CustomEndpointCard(
                        status = cs,
                        onSetKey = { k -> viewModel.setCustomKey(cs.endpoint.id, k) },
                        onDelete = { viewModel.deleteCustomEndpoint(cs.endpoint.id) }
                    )
                }
            }
        }

        "secrets" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showAddSecretDialog = true }) {
                        Icon(Icons.Outlined.Add, null, tint = ModernAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = ModernAccent, fontSize = 13.sp)
                    }
                }
                Text(
                    "Register an API key by name. The agent references it by name only — the raw value never enters the model.",
                    color = forgePalette.textDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                if (namedSecretStatuses.isEmpty()) {
                    Text("None yet.", color = forgePalette.textDim, fontSize = 12.sp)
                }
                namedSecretStatuses.forEach { ns ->
                    NamedSecretCard(
                        status = ns,
                        onDelete = { viewModel.deleteNamedSecret(ns.secret.name) },
                    )
                }
            }
        }

        "advanced" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdvancedExecutionCard(
                    costThreshold = costThresholdUsd,
                    onSetCostThreshold = { viewModel.setCostThresholdUsd(it) },
                    remoteUrl = remotePythonWorkerUrl,
                    remoteToken = remotePythonWorkerAuthToken,
                    onSetHybrid = { url, token -> viewModel.setHybridExecution(url, token) }
                )
            }
        }

        "prefetch" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PredictivePrefetchCard(
                    enabled = prefetchEnabled,
                    onToggleEnabled = { viewModel.setPrefetchEnabled(it) },
                    allowUnsafe = prefetchAllowUnsafe,
                    onToggleAllowUnsafe = { viewModel.setPrefetchAllowUnsafe(it) }
                )
            }
        }

        "intelligence" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        "backup" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        "padlocks" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityPadlocksCard()
            }
        }

        "channels" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val channelVm: com.forge.os.presentation.screens.channels.ChannelViewModel = hiltViewModel()
                val channelsEnabled by channelVm.channelsEnabled.collectAsState()

                Box(modifier = Modifier.spotlightTarget("settings_channels")) {
                    SettingsToggleRow(
                        title = "Enable channels",
                        subtitle = "Separate memory by context (Work, Personal, etc.)",
                        checked = channelsEnabled,
                        onCheckedChange = { channelVm.setChannelsEnabled(it) }
                    )
                }
                if (channelsEnabled) {
                    SettingsNavRow(
                        icon = Icons.Outlined.ManageAccounts,
                        title = "Manage channels",
                        subtitle = "Create, edit, and organize your channels",
                        onClick = onNavigateToMemories
                    )
                }
            }
        }

        "help" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        "routing" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsNavRow(
                    icon = Icons.Outlined.AltRoute,
                    title = "Model routing",
                    subtitle = "Edit fallback chain & background-caller toggles",
                    onClick = onNavigateToModelRouting
                )
                SettingsNavRow(
                    icon = Icons.Outlined.Lock,
                    title = "Advanced overrides",
                    subtitle = "Per-tool blocked hosts/extensions/configs",
                    onClick = onNavigateToOverrides
                )
                SettingsNavRow(
                    icon = Icons.Outlined.Person,
                    title = "Personality",
                    subtitle = "Customize agent name, traits, communication style",
                    onClick = onNavigateToPersonality
                )
                SettingsNavRow(
                    icon = Icons.Outlined.Backup,
                    title = "Backup & Restore",
                    subtitle = "Create system snapshot or restore from backup",
                    onClick = onNavigateToBackup
                )
            }
        }

        "permissions" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionsCard()
            }
        }

        "apiserver" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiServerCard(
                    running = apiServerRunning,
                    port = apiServerPort,
                    apiKey = apiServerKey,
                    onStart = { viewModel.startApiServer() },
                    onStop = { viewModel.stopApiServer() },
                    onRegenerateToken = { viewModel.regenerateApiToken() }
                )
            }
        }

        "about" -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = forgePalette.surface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ForgeLogo(size = 48.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Forge OS",
                            color = ModernTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "v${com.forge.os.BuildConfig.VERSION_NAME}",
                            color = ModernTextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Built by Forge Labs",
                            color = ModernTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "A division of TheKingsMediaStudio",
                            color = ModernTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
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

// ── Collapsible Section Header ──────────────────────────────────────────────

/**
 * Section header with expand/collapse toggle, icon, optional badge, and optional action.
 * Used to group settings into collapsible sections for progressive disclosure.
 */
@Composable
private fun CollapsibleSectionHeader(
    title: String,
    icon: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    isAdvanced: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (isAdvanced) forgePalette.danger.copy(alpha = 0.08f)
                        else ModernAccent.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 16.sp)
            }
            Spacer(Modifier.width(12.dp))

            // Title
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = ModernTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                    if (isAdvanced) {
                        Spacer(Modifier.width(6.dp))
                        StatusBadge(
                            status = "DEV",
                            color = forgePalette.danger
                        )
                    }
                }
            }

            // Badge
            if (badge != null) {
                Surface(
                    color = forgePalette.surface2,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        badge,
                        color = forgePalette.textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            // Action slot
            if (action != null) {
                action()
            }

            // Chevron
            Text(
                if (collapsed) "▸" else "▾",
                color = forgePalette.textDim,
                fontSize = 14.sp
            )
        }
    }
}
