package com.forge.os.presentation.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.ReadOnlyComposable
import com.forge.os.domain.heartbeat.HealthLevel
import com.forge.os.domain.security.ProviderSpec
import com.forge.os.presentation.theme.LocalForgePalette
import kotlinx.coroutines.launch

private val Orange: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.orange
private val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.bg
private val Surface: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface
private val Surface2: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface2
private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
private val TextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textMuted

// Slash commands available in the input bar
private val SLASH_COMMANDS = listOf(
    "/help"              to "Show all commands and capabilities",
    "/clear"             to "Clear the current conversation",
    "/new"               to "Start a new conversation",
    "/config"            to "Show current agent configuration",
    "/memory"            to "Show memory summary",
    "/cron"              to "Show scheduled jobs",
    "/agents"            to "List sub-agents",
    "/plugins"           to "List installed plugins",
    "/cost"              to "Show API spending",
    "/history"           to "Show cron job history",
    "/tools"             to "List all available tools",
    "/upload"            to "Show upload/temp folder info",
)

@Composable
fun ChatScreen(
    onNavigateToWorkspace: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToHub: () -> Unit = {},
    onNavigateToCompanion: () -> Unit = {},
    onNavigateToConversations: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val closeDrawerThen: (() -> Unit) -> () -> Unit = { action ->
        { drawerScope.launch { drawerState.close() }; action() }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Surface) {
                Spacer(Modifier.height(12.dp))
                Text("  FORGE", color = Orange, fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp, letterSpacing = 3.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SideNavGroup("WORKSPACE")
                SideNavItem("Workspace files", Icons.Default.Folder,
                    onClick = closeDrawerThen(onNavigateToWorkspace))
                SideNavGroup("MODULES")
                SideNavItem("Hub", Icons.Default.Apps,
                    onClick = closeDrawerThen(onNavigateToHub))
                SideNavItem("Status", Icons.Default.MonitorHeart,
                    onClick = closeDrawerThen(onNavigateToStatus))
                SideNavItem("Browser", Icons.Default.Language,
                    onClick = closeDrawerThen(onNavigateToBrowser))
                SideNavGroup("HISTORY")
                SideNavItem("Conversations", Icons.Default.History,
                    onClick = closeDrawerThen(onNavigateToConversations))
                SideNavItem("Companion", Icons.Default.Chat,
                    onClick = closeDrawerThen(onNavigateToCompanion))
                SideNavGroup("SETTINGS")
                SideNavItem("Settings", Icons.Default.Settings,
                    onClick = closeDrawerThen(onNavigateToSettings))
            }
        }
    ) {
        ChatScreenContent(
            viewModel = viewModel,
            onOpenDrawer = { drawerScope.launch { drawerState.open() } },
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToStatus = onNavigateToStatus,
            onNavigateToCompanion = onNavigateToCompanion,
            onNavigateToBrowser = onNavigateToBrowser,
            onNavigateToWorkspace = onNavigateToWorkspace,
            onNavigateToHub = onNavigateToHub,
        )
    }
}

@Composable
private fun SideNavGroup(label: String) {
    Text("  $label", color = TextMuted, fontFamily = FontFamily.Monospace,
        fontSize = 10.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun SideNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
        selected = false,
        icon = { Icon(icon, label, tint = TextMuted, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun ChatScreenContent(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToCompanion: () -> Unit,
    onNavigateToBrowser: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToHub: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val available by viewModel.availableSpecs.collectAsState()
    val selected by viewModel.selectedSpec.collectAsState()
    val auto by viewModel.autoRoute.collectAsState()
    val cost by viewModel.costSnapshot.collectAsState()
    val sysStatus by viewModel.systemStatus.collectAsState()
    val inputRequest by viewModel.pendingInputRequest.collectAsState()
    val costApproval by viewModel.pendingCostApproval.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showSlashSuggestions by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }

    val commandOptions = remember {
        listOf(
            com.forge.os.presentation.screens.common.CommandOption("Settings", "Agent & API config", "⚙", onNavigateToSettings),
            com.forge.os.presentation.screens.common.CommandOption("Status", "System heartbeat & health", "🩺", onNavigateToStatus),
            com.forge.os.presentation.screens.common.CommandOption("Browser", "Agent-controlled web", "🌐", onNavigateToBrowser),
            com.forge.os.presentation.screens.common.CommandOption("Companion", "Friend mode interactions", "💛", onNavigateToCompanion),
            com.forge.os.presentation.screens.common.CommandOption("Workspace", "File manager & shell", "📂", onNavigateToWorkspace),
            com.forge.os.presentation.screens.common.CommandOption("Clear Chat", "Start fresh session", "🗑", { viewModel.clearMessages() }),
            com.forge.os.presentation.screens.common.CommandOption("New Job", "Schedule a cron task", "⏰", { onNavigateToHub() /* navigate to hub then cron */ }),
        )
    }

    // Filter slash commands by what user has typed
    val slashSuggestions = remember(inputText) {
        if (inputText.startsWith("/")) {
            SLASH_COMMANDS.filter { (cmd, _) -> cmd.startsWith(inputText, ignoreCase = true) }
        } else emptyList()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(messages.size - 1) }
    }
    LaunchedEffect(Unit) { viewModel.refreshAvailableSpecs() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(Surface).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Menu, "Menu", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("⚡", fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text("FORGE", color = Orange, fontFamily = FontFamily.Monospace,
                fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible = isLoading) {
                NeuralPulse()
            }
            if (cost.sessionCalls > 0 || cost.lifetimeUsd > 0.0) {
                Box(Modifier.background(Surface2, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text("$%.3f".format(cost.sessionUsd.coerceAtLeast(cost.lastCallUsd)),
                        color = Orange, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(6.dp))
            }
            val hbColor = when (sysStatus.overallHealth) {
                HealthLevel.HEALTHY -> Color(0xFF22c55e)
                HealthLevel.WARNING -> Color(0xFFf59e0b)
                HealthLevel.CRITICAL -> Color(0xFFef4444)
                HealthLevel.DOWN -> Color(0xFF6b7280)
            }
            Text("●", color = hbColor, fontSize = 12.sp,
                modifier = Modifier.clickable { onNavigateToStatus() }.padding(horizontal = 4.dp))
            // Browser quick-launch
            Text("🌐", fontSize = 14.sp,
                modifier = Modifier.clickable { onNavigateToBrowser() }.padding(horizontal = 4.dp))
            Text("🔍", fontSize = 14.sp,
                modifier = Modifier.clickable { showCommandPalette = true }.padding(horizontal = 4.dp))
            Text("💛", fontSize = 14.sp,
                modifier = Modifier.clickable { onNavigateToCompanion() }.padding(horizontal = 6.dp))
        }

        ModelPickerChip(
            available = available, selected = selected, autoRoute = auto,
            onSelect = viewModel::selectSpec, onAutoRoute = { viewModel.setAutoRoute(true) }
        )

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 2 }) {
                    MessageBubble(msg, onOpenSettings = onNavigateToSettings)
                }
            }
        }

        // Mid-run user input request card
        AnimatedVisibility(visible = inputRequest != null) {
            inputRequest?.let { req ->
                var clarificationText by remember { mutableStateOf("") }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0c1a0a))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Agent needs info:", color = Color(0xFF4ade80),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(req.question, color = TextPrimary, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = clarificationText,
                            onValueChange = { clarificationText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text("Your answer...", color = TextMuted,
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4ade80),
                                unfocusedBorderColor = Color(0xFF1f3f1f),
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            ),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (clarificationText.isNotBlank()) {
                                    viewModel.submitInputResponse(clarificationText)
                                    clarificationText = ""
                                }
                            })
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (clarificationText.isNotBlank()) {
                                    viewModel.submitInputResponse(clarificationText)
                                    clarificationText = ""
                                }
                            },
                            modifier = Modifier
                                .background(Color(0xFF166534), RoundedCornerShape(8.dp))
                                .size(44.dp)
                        ) {
                            Icon(Icons.Default.Send, "Reply", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        // Cost approval dialog (Phase 3)
        AnimatedVisibility(visible = costApproval != null) {
            costApproval?.let { est ->
                AlertDialog(
                    onDismissRequest = { viewModel.rejectCost() },
                    title = {
                        Text("💰 BUDGET GATE", color = Orange, fontFamily = FontFamily.Monospace,
                             fontSize = 14.sp, letterSpacing = 2.sp)
                    },
                    text = {
                        Column {
                            Text("Estimated cost for this task exceeds your threshold.",
                                 color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ESTIMATE", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("$${"%.4f".format(est.estimatedUsd)} USD", color = Orange, fontSize = 12.sp,
                                     fontFamily = FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("THRESHOLD", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("$${"%.4f".format(est.thresholdUsd)} USD", color = TextPrimary, fontSize = 11.sp,
                                     fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Max tokens: ${est.estimatedInputTokens} in / ${est.estimatedOutputTokens} out",
                                 color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("Model: ${est.model}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.approveCost() },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("APPROVE RUN", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.rejectCost() }) {
                            Text("REJECT", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    },
                    containerColor = Surface,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // Slash command suggestions
        AnimatedVisibility(visible = slashSuggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                slashSuggestions.take(6).forEach { (cmd, desc) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { inputText = cmd + " "; showSlashSuggestions = false }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(cmd, color = Orange, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            modifier = Modifier.width(120.dp))
                        Text(desc, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }

        // Input bar
        Row(
            Modifier.fillMaxWidth().background(Surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Message Forge... (type / for commands)", color = TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange, unfocusedBorderColor = Color(0xFF333333),
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Orange
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank() && !isLoading) {
                        viewModel.send(inputText); inputText = ""
                    }
                }),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        viewModel.send(inputText); inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isLoading,
                modifier = Modifier
                    .background(
                        if (inputText.isNotBlank() && !isLoading) Orange else Color(0xFF333333),
                        RoundedCornerShape(8.dp)
                    )
                    .size(48.dp)
            ) {
                Icon(Icons.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        if (showCommandPalette) {
            com.forge.os.presentation.screens.common.CommandPalette(
                options = commandOptions,
                onDismiss = { showCommandPalette = false }
            )
        }
    }
}

@Composable
private fun ModelPickerChip(
    available: List<ProviderSpec>, selected: ProviderSpec?,
    autoRoute: Boolean, onSelect: (ProviderSpec) -> Unit, onAutoRoute: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0d0d0d)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Row(
                Modifier.background(Surface2, RoundedCornerShape(6.dp)).clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (autoRoute) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Orange, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-route", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                } else {
                    Text(selected?.displayLabel ?: "No models — add a key in Settings",
                        color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null, tint = TextMuted, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface)) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Orange, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Auto-route by config", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    },
                    onClick = { onAutoRoute(); expanded = false }
                )
                HorizontalDivider(color = Color(0xFF1f1f1f))
                if (available.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No keys configured", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                        onClick = { expanded = false }, enabled = false
                    )
                } else {
                    available.forEach { spec ->
                        DropdownMenuItem(
                            text = { Text(spec.displayLabel, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            onClick = { onSelect(spec); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, onOpenSettings: () -> Unit) {
    when (msg.role) {
        "user" -> UserBubble(msg.content)
        "assistant" -> if (msg.isError) ErrorBubble(msg, onOpenSettings)
                       else AssistantBubble(msg.content, msg.isStreaming)
        "system" -> SystemBubble(msg.content)
        "tool_call" -> ToolCallChip(msg.toolName ?: "tool", msg.content)
        "tool_result" -> ToolResultBubble(msg.toolName ?: "tool", msg.content, msg.isError)
        "input_request" -> InputRequestBubble(msg.content)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 280.dp)
                .background(Color(0xFF1c1917), RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                .padding(10.dp, 8.dp)
        ) {
            Text(text, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp)) {
            Box(
                Modifier.background(Surface2, RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp))
                    .padding(10.dp, 8.dp)
            ) {
                val displayText = text + if (isStreaming) "▋" else ""
                if (displayText.contains("**") || displayText.contains("`") ||
                    displayText.contains("```") || displayText.contains("# ") ||
                    displayText.contains("- ") || displayText.contains("> ")) {
                    MarkdownText(
                        text = displayText,
                        baseColor = TextPrimary,
                        baseFontSize = 13f
                    )
                } else {
                    Text(displayText, color = TextPrimary, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(msg: ChatMessage, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp)) {
            Box(
                Modifier.background(Color(0xFF1a0a0a), RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp))
                    .padding(10.dp, 8.dp)
            ) {
                Column {
                    Text(msg.content, color = Color(0xFFef4444), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                    msg.errorDetail?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(buildString {
                            append("provider=${err.provider} model=${err.model}")
                            if (err.httpCode > 0) append(" http=${err.httpCode}")
                            err.providerCode?.let { append(" code=$it") }
                            err.requestId?.let { append("\nreq=$it") }
                        }, color = Color(0xFF7f1d1d), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Text("Fix in Settings →", color = Orange, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { onOpenSettings() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemBubble(text: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFF0f172a), RoundedCornerShape(8.dp))
            .padding(10.dp, 8.dp)
    ) {
        Text(text, color = Color(0xFF94a3b8), fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
    }
}

@Composable
private fun InputRequestBubble(question: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFF0c1a0a), RoundedCornerShape(8.dp))
            .padding(10.dp, 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("❓", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(question, color = Color(0xFF86efac), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ToolCallChip(toolName: String, args: String) {
    Row(
        Modifier.background(Color(0xFF0c0a1a), RoundedCornerShape(6.dp)).padding(8.dp, 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚙", color = Color(0xFF818cf8), fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(toolName, color = Color(0xFF818cf8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        val preview = args.take(60).replace("\n", " ")
        if (preview.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(preview + if (args.length > 60) "…" else "",
                color = Color(0xFF4c4f7a), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ToolResultBubble(toolName: String, result: String, isError: Boolean) {
    val accent = if (isError) Color(0xFFef4444) else Color(0xFF22c55e)
    val bg = if (isError) Color(0xFF1a0a0a) else Color(0xFF0a1a0a)
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(6.dp)).padding(8.dp, 6.dp)) {
        Column {
            Text("${if (isError) "✗" else "✓"} $toolName",
                color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            val preview = result.take(300)
            Text(preview + if (result.length > 300) "\n…(${result.length} chars)" else "",
                color = Color(0xFF737373), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        }
    }
}
@Composable
private fun NeuralPulse() {
    val infiniteTransition = rememberInfiniteTransition()
    val palette = LocalForgePalette.current
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer glow
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer(scaleX = scale * 1.5f, scaleY = scale * 1.5f, alpha = alpha * 0.3f)
                    .background(palette.neuralPulse, androidx.compose.foundation.shape.CircleShape)
            )
            // Inner core
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                    .background(palette.neuralPulse, androidx.compose.foundation.shape.CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "thinking...",
            color = palette.neuralPulse,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.graphicsLayer(alpha = alpha)
        )
        Spacer(Modifier.width(8.dp))
    }
}
