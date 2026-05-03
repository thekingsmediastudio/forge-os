package com.forge.os.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.KeyStatus
import com.forge.os.domain.security.ProviderSchema
import androidx.compose.runtime.ReadOnlyComposable
import com.forge.os.presentation.theme.LocalForgePalette
import com.forge.os.presentation.theme.ThemeMode
import kotlinx.coroutines.delay

// Theme-aware accessors. These resolve from the active [LocalForgePalette]
// every recomposition so flipping the theme switcher actually changes colours.
private val Orange: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.orange
private val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.bg
private val Surface: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface
private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
private val TextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textMuted

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToModelRouting: () -> Unit = {},
    onNavigateToOverrides: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
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

    LaunchedEffect(saveMessage) {
        if (saveMessage != null) { delay(3000); viewModel.clearSaveMessage() }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                     tint = TextMuted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("⚙  SETTINGS", color = Orange, fontSize = 16.sp,
                 fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNavigateToDiagnostics, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.BugReport, "Diagnostics",
                     tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Keys stored in Android Keystore (AES-256-GCM)",
             color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        AnimatedVisibility(visible = saveMessage != null) {
            saveMessage?.let {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF052e16), RoundedCornerShape(6.dp))
                        .padding(10.dp, 8.dp)
                ) {
                    Text(it, color = Color(0xFF4ade80), fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // ── Appearance ───────────────────────────────────────────────────
            item {
                Text("APPEARANCE", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            item {
                AppearanceCard(
                    selected = themeMode,
                    onSelect = { viewModel.setThemeMode(it) },
                    hapticEnabled = hapticFeedbackEnabled,
                    onHapticToggle = { viewModel.setHapticFeedbackEnabled(it) }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Model ────────────────────────────────────────────────────────
            item {
                Text("MODEL", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            item {
                CompactModeCard(
                    enabled = compactModeEnabled,
                    onToggle = { viewModel.setCompactModeEnabled(it) }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Built-in Providers ───────────────────────────────────────────
            item {
                Text("BUILT-IN PROVIDERS", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            items(keyStatuses) { status ->
                ApiKeyCard(
                    status = status,
                    onSave = { key -> viewModel.saveKey(status.provider, key) },
                    onDelete = { viewModel.deleteKey(status.provider) }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CUSTOM ENDPOINTS", color = TextMuted, fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Orange)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (customStatuses.isEmpty()) {
                item {
                    Text("None yet. Use Add to wire any OpenAI- or Anthropic-compatible URL.",
                         color = Color(0xFF404040), fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                }
            }
            items(customStatuses) { cs ->
                CustomEndpointCard(
                    status = cs,
                    onSetKey = { k -> viewModel.setCustomKey(cs.endpoint.id, k) },
                    onDelete = { viewModel.deleteCustomEndpoint(cs.endpoint.id) }
                )
            }

            // ── Custom API Keys (named-secret extension) ─────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CUSTOM API KEYS", color = TextMuted, fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showAddSecretDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Orange)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Register an API key by name (e.g. github_pat). The agent " +
                    "references it by name only — the raw value never enters " +
                    "the model. Use it via the secret_list / secret_request tools.",
                    color = Color(0xFF707070), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                )
            }
            if (namedSecretStatuses.isEmpty()) {
                item {
                    Text("None yet.",
                         color = Color(0xFF404040), fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace)
                }
            }
            items(namedSecretStatuses) { ns ->
                NamedSecretCard(
                    status = ns,
                    onDelete = { viewModel.deleteNamedSecret(ns.secret.name) },
                )
            }

            // ── Advanced Execution (Phase 3) ─────────────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Text("ADVANCED EXECUTION", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            item {
                AdvancedExecutionCard(
                    costThreshold = costThresholdUsd,
                    onSetCostThreshold = { viewModel.setCostThresholdUsd(it) },
                    remoteUrl = remotePythonWorkerUrl,
                    remoteToken = remotePythonWorkerAuthToken,
                    onSetHybrid = { url, token -> viewModel.setHybridExecution(url, token) }
                )
            }

            // ── Predictive Prefetch ───────────────────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Text("PREDICTIVE PREFETCH", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            item {
                PredictivePrefetchCard(
                    enabled = prefetchEnabled,
                    onToggleEnabled = { viewModel.setPrefetchEnabled(it) },
                    allowUnsafe = prefetchAllowUnsafe,
                    onToggleAllowUnsafe = { viewModel.setPrefetchAllowUnsafe(it) }
                )
            }

            // ── Intelligence Upgrades (Phase 4) ───────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Text("INTELLIGENCE UPGRADES", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
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

            item {
                Spacer(Modifier.height(16.dp))
                Text("BACKUP", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                BackupCard(
                    loading = backupLoading,
                    onBackup = {
                        viewModel.performBackup { file ->
                            val uri = com.forge.os.data.system.BackupManager(context).getBackupUri(file)
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

            item {
                Spacer(Modifier.height(16.dp))
                Text("OLLAMA NOTE", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Text("For local Ollama, the \"key\" field is the host URL,\ne.g. http://192.168.1.x:11434/v1/",
                     color = Color(0xFF404040), fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
            }

            item { CapabilityPadlocksCard() }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Phase S: Routing & advanced overrides ────────────────────────
            item {
                Text("ROUTING & SECURITY", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            item {
                NavRow(
                    title = "🧭  Model routing",
                    subtitle = "Edit fallback chain & background-caller toggles",
                    onClick = onNavigateToModelRouting,
                )
            }
            item {
                NavRow(
                    title = "🔒  Advanced overrides",
                    subtitle = "Per-tool blocked hosts/extensions/configs (the padlock the agent can't open)",
                    onClick = onNavigateToOverrides,
                )
            }
            item {
                NavRow(
                    title = "💾  Backup & Restore",
                    subtitle = "Create system snapshot or restore from previous backup",
                    onClick = onNavigateToBackup,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
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

@Composable
fun BackupCard(
    loading: Boolean,
    onBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "System Backup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Create a full ZIP archive of your workspace, agent memory, and settings. " +
                "Export this file to safely migrate or restore your OS state later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onBackup,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Zipping System Data...")
                } else {
                    Icon(Icons.Default.Backup, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Generate Full Backup")
                }
            }
        }
    }
}

// ── Named-secret row + add dialog ──────────────────────────────────────────

@Composable
private fun NamedSecretCard(
    status: NamedSecretStatus,
    onDelete: () -> Unit,
) {
    val s = status.secret
    Box(
        Modifier.fillMaxWidth()
            .background(Surface, RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = Orange, fontSize = 13.sp,
                     fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text("[${s.authStyle}]", color = TextMuted, fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(if (status.hasValue) "✓ stored" else "⚠ no value",
                     color = if (status.hasValue) Color(0xFF4ade80) else Color(0xFFef4444),
                     fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, "Delete",
                         tint = Color(0xFFef4444), modifier = Modifier.size(14.dp))
                }
            }
            if (s.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(s.description, color = TextPrimary, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
            }
            val attach = when (s.authStyle) {
                "bearer" -> "Authorization: Bearer …"
                "header" -> "${s.headerName}: …"
                "query"  -> "?${s.queryParam}=…"
                else     -> s.authStyle
            }
            Spacer(Modifier.height(2.dp))
            Text(attach, color = TextMuted, fontSize = 10.sp,
                 fontFamily = FontFamily.Monospace)
        }
    }
}

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
        title = { Text("Add custom API key", fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. github_pat)") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("What is it for?") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                )
                Text("How to attach it:", color = TextMuted, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("bearer", "header", "query").forEach { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { style = opt }
                                .padding(end = 8.dp)
                        ) {
                            RadioButton(selected = style == opt, onClick = { style = opt })
                            Text(opt, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                if (style == "header") {
                    OutlinedTextField(
                        value = headerName, onValueChange = { headerName = it },
                        label = { Text("Header name") }, singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    )
                }
                if (style == "query") {
                    OutlinedTextField(
                        value = queryParam, onValueChange = { queryParam = it },
                        label = { Text("Query parameter name") }, singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    )
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text("Secret value (stored encrypted)") },
                    singleLine = true,
                    visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showValue = !showValue }) {
                            Icon(
                                if (showValue) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Toggle visibility", modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description, style, headerName, queryParam, value) },
                enabled = name.isNotBlank() && value.isNotBlank(),
            ) { Text("Save", color = Orange) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Compact Mode Card ─────────────────────────────────────────────────────────

/**
 * Compact Mode toggle card.
 *
 * The card explains both sides of the choice so users can decide for themselves:
 *   - Enable it  → faster, cheaper, fewer tokens per message
 *   - Leave it off → thorough, full-length replies using your chosen model
 */
@Composable
private fun CompactModeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Compact Mode",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        if (enabled) "ON  —  shorter replies, lower token cost"
                        else         "OFF  —  full replies, normal model routing",
                        color = if (enabled) Color(0xFF4ade80) else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Orange,
                        uncheckedThumbColor = Color(0xFF737373),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF1f1f1f))
            Spacer(Modifier.height(10.dp))

            // "Enable it if" column
            Text(
                "Turn ON if you:",
                color = Color(0xFF4ade80),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "Want quick, to-the-point answers",
                "Are watching your API spend",
                "Use a provider with a small free quota",
                "Don't need long explanations or code blocks"
            ).forEach { line ->
                Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text("·  ", color = Color(0xFF4ade80), fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace)
                    Text(line, color = TextMuted, fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // "Leave it off if" column
            Text(
                "Leave OFF if you:",
                color = Color(0xFF737373),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "Want thorough, detailed responses",
                "Do complex coding or writing tasks",
                "Have an unlimited or high-quota API key",
                "Want the agent to use your chosen model fully"
            ).forEach { line ->
                Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text("·  ", color = Color(0xFF404040), fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace)
                    Text(line, color = Color(0xFF404040), fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF1f1f1f))
            Spacer(Modifier.height(8.dp))

            Text(
                "When on: replies capped at 512 tokens · last 8 messages sent · routes to Groq by default.",
                color = Color(0xFF404040),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

// ── API Key Card ──────────────────────────────────────────────────────────────

@Composable
private fun ApiKeyCard(
    status: KeyStatus,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var inputKey by remember(status.provider) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(!status.hasKey) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.provider.displayName, color = TextPrimary, fontSize = 13.sp,
                         fontFamily = FontFamily.Monospace)
                    Text(status.provider.baseUrl, color = TextMuted, fontSize = 10.sp,
                         fontFamily = FontFamily.Monospace)
                }
                StatusPill(status.hasKey)
                if (status.hasKey) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Delete, "Delete key",
                             tint = Color(0xFF737373), modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (status.hasKey && !editing) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.maskedKey, color = TextMuted, fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Orange)) {
                        Text("Change", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (editing || !status.hasKey) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputKey, onValueChange = { inputKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(if (status.provider == ApiKeyProvider.OLLAMA)
                                "http://host:11434/v1/" else "sk-...",
                             color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    visualTransformation = if (showKey) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                 null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Orange
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, shape = RoundedCornerShape(6.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.hasKey) {
                        OutlinedButton(
                            onClick = { editing = false; inputKey = "" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                        ) { Text("Cancel", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                    Button(
                        onClick = { onSave(inputKey); editing = false; inputKey = "" },
                        enabled = inputKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange)
                    ) {
                        Text("Save Key", fontSize = 12.sp,
                             fontFamily = FontFamily.Monospace, color = Color.White)
                    }
                }
            }
        }
    }
}

// ── Custom Endpoint Card ──────────────────────────────────────────────────────

@Composable
private fun CustomEndpointCard(
    status: CustomEndpointStatus,
    onSetKey: (String) -> Unit,
    onDelete: () -> Unit
) {
    var inputKey by remember(status.endpoint.id) { mutableStateOf("") }
    var editing by remember { mutableStateOf(!status.hasKey) }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.endpoint.name, color = TextPrimary, fontSize = 13.sp,
                         fontFamily = FontFamily.Monospace)
                    Text("${status.endpoint.baseUrl}  •  ${status.endpoint.schema.name}  •  ${status.endpoint.defaultModel}",
                         color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                StatusPill(status.hasKey)
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Delete, "Delete endpoint",
                         tint = Color(0xFF737373), modifier = Modifier.size(16.dp))
                }
            }
            if (status.hasKey && !editing) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.maskedKey, color = TextMuted, fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Orange)) {
                        Text("Change", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputKey, onValueChange = { inputKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("API key", color = TextMuted, fontSize = 12.sp,
                                         fontFamily = FontFamily.Monospace) },
                    visualTransformation = if (showKey) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                 null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Orange
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, shape = RoundedCornerShape(6.dp)
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { onSetKey(inputKey); editing = false; inputKey = "" },
                    enabled = inputKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)
                ) {
                    Text("Save Key", fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
        }
    }
}

// ── Appearance Card ───────────────────────────────────────────────────────────

@Composable
private fun AppearanceCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    hapticEnabled: Boolean,
    onHapticToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Theme", color = TextPrimary, fontSize = 13.sp,
                 fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(2.dp))
            Text("Applies immediately across the app",
                 color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            ThemeMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Orange,
                            unselectedColor = Color(0xFF404040)
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(mode.displayName, color = TextPrimary, fontSize = 12.sp,
                         fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1f1f1f))
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Haptic Feedback", color = TextPrimary, fontSize = 13.sp,
                         fontFamily = FontFamily.Monospace)
                    Text("Tactile response when agent is active",
                         color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Switch(
                    checked = hapticEnabled,
                    onCheckedChange = onHapticToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Orange,
                    )
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun StatusPill(set: Boolean) {
    val (bg, fg, label) = if (set)
        Triple(Color(0xFF052e16), Color(0xFF4ade80), "● SET")
    else
        Triple(Color(0xFF1a0a0a), Color(0xFF737373), "○ EMPTY")
    Box(
        Modifier.background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = fg, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

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
        containerColor = Surface,
        titleContentColor = Orange,
        textContentColor = TextPrimary,
        title = { Text("Add custom endpoint", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField("Name", name, "e.g. Local LM Studio") { name = it }
                DialogField("Base URL", url, "https://host/v1/") { url = it }
                DialogField("Default model", model, "model-id") { model = it }
                DialogField("API key", key, "sk-...", isSecret = true) { key = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Schema: ", color = TextMuted, fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = schema == ProviderSchema.OPENAI,
                        onClick = { schema = ProviderSchema.OPENAI },
                        label = { Text("OpenAI", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace) }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = schema == ProviderSchema.ANTHROPIC,
                        onClick = { schema = ProviderSchema.ANTHROPIC },
                        label = { Text("Anthropic", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, schema, model, key) },
                enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank()
            ) {
                Text("Add", color = Orange, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun DialogField(
    label: String, value: String, placeholder: String,
    isSecret: Boolean = false, onChange: (String) -> Unit
) {
    Column {
        Text(label, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = TextMuted, fontSize = 12.sp,
                                 fontFamily = FontFamily.Monospace) },
            visualTransformation = if (isSecret) PasswordVisualTransformation()
                                   else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Orange
            ),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            singleLine = true, shape = RoundedCornerShape(6.dp)
        )
    }
}

// ── Padlock / Capabilities ────────────────────────────────────────────────────

/**
 * Phase R — surface the agent control plane's capability padlocks. Every
 * capability is shown with its category, current state, and a switch that
 * calls `setByUser`. Toggling a switch counts as explicit user consent for
 * the underlying gated tool.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ControlPlaneEntryPoint {
    fun controlPlane(): com.forge.os.domain.control.AgentControlPlane
}

@Composable
private fun CapabilityPadlocksCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val plane = remember {
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(ctx.applicationContext, ControlPlaneEntryPoint::class.java)
            .controlPlane()
    }
    val states by plane.states.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒 PADLOCKS", color = Orange, fontFamily = FontFamily.Monospace,
                     fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show",
                         color = TextMuted, fontSize = 11.sp,
                         fontFamily = FontFamily.Monospace)
                }
            }
            Text("Per-tool consent gates. Toggle on to grant the agent permission " +
                 "for that capability; toggle off to revoke. Switches persist.",
                 color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                 lineHeight = 15.sp)
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                plane.capabilities.groupBy { it.category }.forEach { (cat, caps) ->
                    Text(cat.uppercase(), color = Orange,
                         fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                         letterSpacing = 1.sp,
                         modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    caps.forEach { cap ->
                        val on = states[cap.id]?.enabled ?: cap.defaultEnabled
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cap.title.ifBlank { cap.id }, color = TextPrimary,
                                     fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                if (cap.description.isNotBlank()) {
                                    Text(cap.description, color = TextMuted,
                                         fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                         lineHeight = 13.sp)
                                }
                            }
                            Switch(
                                checked = on,
                                onCheckedChange = { wanted -> plane.setByUser(cap.id, wanted) },
                            )
                        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Predictive Prefetch",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        if (enabled) "ON  —  agent anticipates your next request"
                        else         "OFF  —  no background prediction",
                        color = if (enabled) Color(0xFF4ade80) else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Orange,
                        uncheckedThumbColor = Color(0xFF737373),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "When enabled, Forge analyses your recent activity and proactively " +
                "executes predicted tool calls in the background. Results are cached " +
                "so the next matching request returns instantly.",
                color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 14.sp
            )

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF1f1f1f))
                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Allow Unsafe Tools",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (allowUnsafe)
                                    "⚠ Prefetch may run write/mutating tools (git_push, file_delete, etc.)"
                                else
                                    "Safe mode — only read-only tools (browser_get_html, git_log, etc.)",
                                color = if (allowUnsafe) Color(0xFFfbbf24) else TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = allowUnsafe,
                            onCheckedChange = onToggleAllowUnsafe,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFfbbf24),
                                uncheckedThumbColor = Color(0xFF737373),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }
                }
            }
        }
    }
}

// ── Phase S: NavRow ─────────────────────────────────────────────────────────

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    val palette = LocalForgePalette.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = palette.textPrimary, fontSize = 13.sp,
                     fontFamily = FontFamily.Monospace)
                Text(subtitle, color = palette.textMuted, fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
            }
            Text("›", color = palette.textMuted, fontSize = 18.sp,
                 fontFamily = FontFamily.Monospace)
        }
    }
}

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Cost Threshold
            Column {
                Text("Budget Gate (USD)", color = TextPrimary, fontSize = 13.sp,
                     fontFamily = FontFamily.Monospace)
                Text("Agent pauses for approval if estimated run cost exceeds this. 0.0 = disabled.",
                     color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputThreshold, onValueChange = { inputThreshold = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("0.05", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            cursorColor = Orange
                        ),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSetCostThreshold(inputThreshold.replace(',', '.').toDoubleOrNull() ?: 0.0) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1f1f1f), contentColor = Orange),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(12.dp, 4.dp)
                    ) {
                        Text("Set", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1f1f1f))

            // Hybrid Execution
            Column {
                Text("Remote Python Worker (GPU)", color = TextPrimary, fontSize = 13.sp,
                     fontFamily = FontFamily.Monospace)
                Text("Auto-routes heavy ML scripts to this endpoint instead of on-device Chaquopy.",
                     color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = inputUrl, onValueChange = { inputUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Worker URL (e.g. https://gpu.lab/run)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Orange
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = inputToken, onValueChange = { inputToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auth Token (Optional)") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                 null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Orange
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSetHybrid(inputUrl, inputToken) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Save Hybrid Settings", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IntelToggleRow(
                title = "Autonomous Learning (Reflection)",
                subtitle = "Agent analyzes errors and circular loops to improve next time",
                enabled = reflection,
                onToggle = onReflectionToggle
            )
            HorizontalDivider(color = Color(0xFF1f1f1f))
            IntelToggleRow(
                title = "Long-term Memory (RAG)",
                subtitle = "Prioritizes past project knowledge in every prompt",
                enabled = memoryRag,
                onToggle = onMemoryRagToggle
            )
            HorizontalDivider(color = Color(0xFF1f1f1f))
            IntelToggleRow(
                title = "Vision Processing",
                subtitle = "Allows agent to see and reason about screenshots/images",
                enabled = vision,
                onToggle = onVisionToggle
            )
            HorizontalDivider(color = Color(0xFF1f1f1f))
            IntelToggleRow(
                title = "Advanced Reasoning",
                subtitle = "Uses specialized deep-thought models for complex tasks",
                enabled = reasoning,
                onToggle = onReasoningToggle
            )
            
            Spacer(Modifier.height(4.dp))
            Text(
                "NOTE: These features may increase token usage and response latency.",
                color = Color(0xFF404040), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun IntelToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(subtitle, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Orange,
                uncheckedThumbColor = Color(0xFF737373),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}
