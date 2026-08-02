package com.forge.os.presentation.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import com.forge.os.presentation.components.ForgeLogo
import com.forge.os.presentation.components.spotlightTarget
import com.forge.os.presentation.screens.ChatViewModel
import com.forge.os.presentation.theme.LocalForgePalette
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.ChatMessage
import kotlinx.coroutines.launch

// Modern color palette - now using theme system
private val ModernBg: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.bg
private val ModernSurface: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.surface
private val ModernSurfaceHover: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.surface2
private val ModernAccent: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.orange
private val ModernAccentHover: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.orange.copy(alpha = 0.8f)
private val ModernTextPrimary: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.textPrimary
private val ModernTextSecondary: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.textMuted
private val ModernBorder: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.border
private val ModernSurfaceElevated: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = forgePalette.surfaceElevated

/**
 * Modern chat screen with ChatGPT/Claude-inspired design.
 * Features:
 * - Clean, spacious layout
 * - Smooth animations
 * - Modern message bubbles
 * - Floating action buttons
 * - Gradient accents
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernChatScreen(
    currentRoute: String = "chat",
    onNavigateToWorkspace: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStatus: () -> Unit = {},
    onNavigateToHub: () -> Unit = {},
    onNavigateToCompanion: () -> Unit = {},
    onNavigateToConversations: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val inputRequest by viewModel.pendingInputRequest.collectAsState()
    val availableSpecs by viewModel.availableSpecs.collectAsState()
    val selectedSpec by viewModel.selectedSpec.collectAsState()
    val voiceVm: com.forge.os.presentation.screens.voice.VoiceInputViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    
    // Channel state
    val channelVm: com.forge.os.presentation.screens.channels.ChannelViewModel = hiltViewModel()
    val channelsEnabled by channelVm.channelsEnabled.collectAsState()
    val currentChannel by channelVm.currentChannel.collectAsState()
    val channels by channelVm.channels.collectAsState()
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showVoiceMode by remember { mutableStateOf(false) }
    
    // Tutorial state
    val tutorialManager: com.forge.os.domain.tutorial.TutorialManager = androidx.hilt.navigation.compose.hiltViewModel<com.forge.os.presentation.screens.chat.TutorialViewModel>().tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    
    // Check if tutorial should be shown on first launch
    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowChatTutorial()) {
            kotlinx.coroutines.delay(500) // Wait for UI to settle
            showTutorial = true
        }
    }

    // Recipes — if the Recipes screen handed us a prompt via "Use in Chat",
    // pre-fill the input so the user can edit before sending.
    LaunchedEffect(Unit) {
        PendingRecipeSeed.consume()?.let { inputText = it }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModernBg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Modern Header
            ModernHeader(
                onMenuClick = { showMenu = true },
                isLoading = isLoading,
                selectedSpec = selectedSpec,
                availableSpecs = availableSpecs,
                onSelectSpec = { viewModel.selectSpec(it) },
                onNavigateToSettings = onNavigateToSettings,
                onVoiceMode = { showVoiceMode = true },
                onClearChat = { viewModel.clearMessages() },
                channelsEnabled = channelsEnabled,
                currentChannel = currentChannel,
                channels = channels,
                onChannelSelect = { channelVm.switchChannel(it.id) },
            )
            
            // Messages Area
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(onSuggestionClick = { suggestion ->
                        viewModel.send(suggestion)
                    })
                } else {
                    val renderGroups = remember(messages) { groupMessages(messages) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(renderGroups, key = { it.key }) { group ->
                            when (group) {
                                is MessageGroup.Single -> ModernMessageBubble(
                                    message = group.message,
                                    onRetry = { viewModel.retryLast() },
                                    onSpeak = { text -> voiceVm.speak(text) },
                                )
                                is MessageGroup.AiActivity -> AiActivityMessage(
                                    steps = group.steps,
                                    response = group.response,
                                    isStreaming = group.isStreaming,
                                    onSpeak = { text -> voiceVm.speak(text) },
                                )
                            }
                        }

                        // Loading indicator
                        if (isLoading) {
                            item {
                                TypingIndicator()
                            }
                        }
                    }
                }
            }
            
            // Input request card
            AnimatedVisibility(visible = inputRequest != null) {
                inputRequest?.let { req ->
                    InputRequestCard(
                        question = req.question,
                        onSubmit = { response ->
                            viewModel.submitInputResponse(response)
                        }
                    )
                }
            }
            
            // Modern Input Bar
            ModernInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank() && !isLoading) {
                        viewModel.send(inputText)
                        inputText = ""
                    }
                },
                onStop = { viewModel.stopGeneration() },
                onVoiceMode = { showVoiceMode = true },
                onClearChat = { viewModel.clearMessages() },
                isLoading = isLoading,
                enabled = !isLoading
            )
        }
        
        // Side Menu
        if (showMenu) {
            ModernSideMenu(
                currentRoute = currentRoute,
                onDismiss = { showMenu = false },
                onNavigateToWorkspace = { showMenu = false; onNavigateToWorkspace() },
                onNavigateToSettings = { showMenu = false; onNavigateToSettings() },
                onNavigateToStatus = { showMenu = false; onNavigateToStatus() },
                onNavigateToHub = { showMenu = false; onNavigateToHub() },
                onNavigateToCompanion = { showMenu = false; onNavigateToCompanion() },
                onNavigateToConversations = { showMenu = false; onNavigateToConversations() },
                onNavigateToBrowser = { showMenu = false; onNavigateToBrowser() }
            )
        }

        // Voice Mode Overlay
        if (showVoiceMode) {
            com.forge.os.presentation.screens.voice.VoiceModeOverlay(
                onDismiss = { showVoiceMode = false }
            )
        }
        
        // Tutorial Overlay
        if (showTutorial) {
            val tutorialSteps = listOf(
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Welcome to Forge OS",
                    description = "This is your AI assistant. Ask anything, and it will help you with tasks, answer questions, and more.",
                    targetKey = null, // No spotlight for welcome
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Model Selection",
                    description = "Tap the model pill to switch between AI providers. Green dot means connected.",
                    targetKey = "model_pill",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Menu",
                    description = "Access workspace, settings, hub, and more from the side menu.",
                    targetKey = "menu_button",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Input Area",
                    description = "Type your message here. Tap + for attachments, voice input, and more options.",
                    targetKey = "input_field",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
                ),
                com.forge.os.presentation.components.CoachMarkStep(
                    title = "Send & Stop",
                    description = "Tap the arrow to send. While generating, tap the red stop button to cancel.",
                    targetKey = "send_button",
                    tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
                )
            )
            
            com.forge.os.presentation.components.CoachMarkOverlay(
                steps = tutorialSteps,
                currentStep = tutorialStep,
                onNext = { tutorialStep++ },
                onSkip = {
                    showTutorial = false
                    tutorialManager.markChatTutorialShown()
                },
                onDone = {
                    showTutorial = false
                    tutorialManager.markChatTutorialShown()
                }
            )
        }
    }
}

@Composable
private fun ModernHeader(
    onMenuClick: () -> Unit,
    isLoading: Boolean,
    selectedSpec: com.forge.os.domain.security.ProviderSpec?,
    availableSpecs: List<com.forge.os.domain.security.ProviderSpec>,
    onSelectSpec: (com.forge.os.domain.security.ProviderSpec) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onVoiceMode: () -> Unit = {},
    onClearChat: () -> Unit = {},
    channelsEnabled: Boolean = false,
    currentChannel: com.forge.os.domain.channel.Channel = com.forge.os.domain.channel.Channel.GENERAL,
    channels: List<com.forge.os.domain.channel.Channel> = emptyList(),
    onChannelSelect: (com.forge.os.domain.channel.Channel) -> Unit = {},
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = forgePalette.surfaceGlass,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hamburger
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(40.dp)
                    .then(com.forge.os.presentation.components.spotlightTarget("menu_button"))
            ) {
                Icon(
                    Icons.Outlined.Menu,
                    "Menu",
                    tint = ModernTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Logo + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                ForgeLogo(size = 32.dp)

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        "Forge OS",
                        color = ModernTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        if (isLoading) "Thinking..." else (selectedSpec?.displayLabel ?: "Auto"),
                        color = ModernTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Channel switcher (when enabled)
            if (channelsEnabled) {
                com.forge.os.presentation.components.ChannelSwitcher(
                    currentChannel = currentChannel,
                    channels = channels,
                    onChannelSelect = onChannelSelect
                )
                Spacer(Modifier.width(8.dp))
            }

            // Model pill with status dot
            Box {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showModelMenu = true }
                        .then(com.forge.os.presentation.components.spotlightTarget("model_pill")),
                    color = ModernSurface,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Green status dot
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(forgePalette.success, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = selectedSpec?.displayLabel ?: "Auto",
                            color = ModernTextPrimary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 100.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false },
                    modifier = Modifier
                        .background(ModernSurfaceElevated)
                        .widthIn(min = 200.dp, max = 300.dp)
                ) {
                    // Auto-route option
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = ModernAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Auto-route",
                                    color = ModernTextPrimary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        },
                        onClick = {
                            // Auto-route logic handled by viewModel
                            showModelMenu = false
                        }
                    )
                    
                    if (availableSpecs.isNotEmpty()) {
                        HorizontalDivider(color = ModernBorder)
                        availableSpecs.forEach { spec ->
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            spec.displayLabel,
                                            color = ModernTextPrimary,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2
                                        )
                                        val providerLabel = when (spec) {
                                            is com.forge.os.domain.security.ProviderSpec.Builtin ->
                                                spec.provider.displayName
                                            is com.forge.os.domain.security.ProviderSpec.Custom ->
                                                spec.endpoint.name
                                        }
                                        Text(
                                            "$providerLabel • ${spec.effectiveModel}",
                                            color = ModernTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectSpec(spec)
                                    showModelMenu = false
                                }
                            )
                        }
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "No keys configured",
                                    color = ModernTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            onClick = { showModelMenu = false },
                            enabled = false
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // Overflow menu (⋮)
            Box {
                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        "More",
                        tint = ModernTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                    modifier = Modifier.background(ModernSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Voice mode", color = ModernTextPrimary, fontSize = 14.sp) },
                        onClick = { showOverflowMenu = false; onVoiceMode() },
                        leadingIcon = { Icon(Icons.Filled.Mic, null, tint = ModernTextSecondary, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear chat", color = ModernTextPrimary, fontSize = 14.sp) },
                        onClick = { showOverflowMenu = false; onClearChat() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ModernTextSecondary, modifier = Modifier.size(20.dp)) }
                    )
                    HorizontalDivider(color = forgePalette.divider)
                    DropdownMenuItem(
                        text = { Text("Settings", color = ModernTextPrimary, fontSize = 14.sp) },
                        onClick = { showOverflowMenu = false; onNavigateToSettings() },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null, tint = ModernTextSecondary, modifier = Modifier.size(20.dp)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onSuggestionClick: (String) -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            // Static ember logo
            ForgeLogo(size = 56.dp, animated = false)

            Text(
                "What can I help you build?",
                color = ModernTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                "Ask me to write code, debug issues,\nor explore your workspace.",
                color = ModernTextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // Suggestion chips — wrapping row
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickActionChip(
                    icon = Icons.Outlined.Code,
                    label = "Review my code",
                    onClick = { onSuggestionClick("Review my code") }
                )
                QuickActionChip(
                    icon = Icons.Outlined.BugReport,
                    label = "Fix a bug",
                    onClick = { onSuggestionClick("Fix a bug") }
                )
                QuickActionChip(
                    icon = Icons.Outlined.Description,
                    label = "Explain this file",
                    onClick = { onSuggestionClick("Explain this file") }
                )
                QuickActionChip(
                    icon = Icons.Outlined.PlayArrow,
                    label = "Run a script",
                    onClick = { onSuggestionClick("Run a script") }
                )
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
        color = ModernSurface,
        shape = RoundedCornerShape(20.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = ModernAccent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                label,
                color = ModernTextPrimary,
                fontSize = 13.sp
            )
        }
    }
}

// ── Message grouping for single-bubble activity pattern ─────────────────────

/** Represents either a standalone message or a grouped AI activity sequence. */
private sealed class MessageGroup {
    abstract val key: String

    data class Single(val message: ChatMessage) : MessageGroup() {
        override val key: String get() = message.id
    }

    data class AiActivity(
        val steps: List<ChatMessage>,
        val response: ChatMessage?,
        val isStreaming: Boolean,
        val groupId: String,
    ) : MessageGroup() {
        override val key: String get() = groupId
    }
}

/**
 * Groups consecutive tool_call / tool_result messages followed by an assistant
 * message into a single [MessageGroup.AiActivity]. Standalone messages pass
 * through as [MessageGroup.Single].
 */
private fun groupMessages(messages: List<ChatMessage>): List<MessageGroup> {
    val groups = mutableListOf<MessageGroup>()
    val pendingSteps = mutableListOf<ChatMessage>()

    for (msg in messages) {
        when (msg.role) {
            "tool_call", "tool_result" -> {
                pendingSteps.add(msg)
            }
            "assistant" -> {
                if (pendingSteps.isNotEmpty()) {
                    groups.add(
                        MessageGroup.AiActivity(
                            steps = pendingSteps.toList(),
                            response = msg,
                            isStreaming = msg.isStreaming,
                            groupId = pendingSteps.first().id,
                        )
                    )
                    pendingSteps.clear()
                } else {
                    groups.add(MessageGroup.Single(msg))
                }
            }
            else -> {
                if (pendingSteps.isNotEmpty()) {
                    groups.add(
                        MessageGroup.AiActivity(
                            steps = pendingSteps.toList(),
                            response = null,
                            isStreaming = false,
                            groupId = pendingSteps.first().id,
                        )
                    )
                    pendingSteps.clear()
                }
                groups.add(MessageGroup.Single(msg))
            }
        }
    }

    if (pendingSteps.isNotEmpty()) {
        groups.add(
            MessageGroup.AiActivity(
                steps = pendingSteps.toList(),
                response = null,
                isStreaming = false,
                groupId = pendingSteps.first().id,
            )
        )
    }

    return groups
}

// ── AI Activity Message (single-bubble pattern) ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiActivityMessage(
    steps: List<ChatMessage>,
    response: ChatMessage?,
    isStreaming: Boolean,
    onSpeak: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showSheet by remember { mutableStateOf(false) }

    val runningStep = steps.lastOrNull { it.role == "tool_call" }
    val doneCount = steps.count { it.role == "tool_result" && !it.isError }
    val hasError = steps.any { it.isError }
    val isRunning = response == null || isStreaming

    val summaryText = when {
        isRunning && runningStep != null -> "Running ${runningStep.toolName ?: "tool"}…"
        isRunning -> "Reasoning…"
        hasError -> "Completed with errors"
        else -> "Completed $doneCount step${if (doneCount != 1) "s" else ""}"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        ForgeLogo(size = 32.dp)
        Spacer(Modifier.width(12.dp))

        Surface(
            modifier = Modifier.widthIn(max = 600.dp),
            color = ModernSurface,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        ) {
            Column {
                // ── Activity head (collapsible) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Status dot
                    val dotColor = when {
                        isRunning -> forgePalette.thinking
                        hasError -> forgePalette.danger
                        else -> forgePalette.success
                    }
                    val dotAlpha by if (isRunning) {
                        rememberInfiniteTransition(label = "activity_pulse").animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
                            label = "dot_pulse"
                        )
                    } else {
                        remember { mutableStateOf(1f) }
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(dotColor.copy(alpha = dotAlpha), CircleShape)
                    )

                    // Title
                    Text(
                        summaryText,
                        color = ModernTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )

                    // Meta badge
                    if (isRunning) {
                        Text(
                            "live",
                            color = forgePalette.thinking,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    // Chevron
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (expanded) 90f else 0f,
                        animationSpec = tween(200),
                        label = "chevron"
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = ModernTextSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }

                // ── Activity panel (expandable steps) ──
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                    ) {
                        HorizontalDivider(color = forgePalette.divider)
                        Spacer(Modifier.height(4.dp))
                        steps.forEach { step ->
                            ActivityStepRow(step)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // ── AI response text ──
                if (response != null) {
                    HorizontalDivider(color = forgePalette.divider)
                    val displayText = response.content + if (isStreaming) " ▋" else ""
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { if (!isStreaming) showSheet = true },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            com.forge.os.presentation.screens.MarkdownText(
                                text = displayText,
                                baseColor = ModernTextPrimary,
                                baseFontSize = 14f
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press actions
    if (showSheet && response != null) {
        BubbleActionsSheet(
            onDismiss = { showSheet = false },
            actions = listOf(
                BubbleAction("📋 Copy", Icons.Outlined.ContentCopy) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(response.content))
                },
                BubbleAction("🔊 Speak", Icons.Outlined.VolumeUp) {
                    onSpeak(response.content)
                }
            )
        )
    }
}

/** A single step row inside the activity panel. */
@Composable
private fun ActivityStepRow(step: ChatMessage) {
    val isToolCall = step.role == "tool_call"
    val isError = step.isError

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Step dot
        val stepDotColor = when {
            isError -> forgePalette.danger
            isToolCall -> forgePalette.thinking
            else -> forgePalette.success
        }
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .background(stepDotColor, CircleShape)
        )

        Column(modifier = Modifier.weight(1f)) {
            // Step title
            Text(
                step.toolName ?: if (isToolCall) "tool_call" else "tool_result",
                color = if (isError) forgePalette.danger else ModernTextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            // Step preview
            if (step.content.isNotBlank()) {
                val preview = if (step.content.length > 120) step.content.take(120) + "…" else step.content
                Text(
                    preview,
                    color = ModernTextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ModernMessageBubble(message: ChatMessage, onRetry: () -> Unit, onSpeak: (String) -> Unit) {
    when (message.role) {
        "user"          -> ModernUserBubble(message.content)
        "assistant"     -> if (message.isError) ModernErrorBubble(message, onRetry)
                           else ModernAssistantBubble(message.content, message.isStreaming, onSpeak)
        "tool_call"     -> ModernToolCallChip(message.toolName ?: "tool", message.content)
        "tool_result"   -> {
            ModernToolResultBubble(message.toolName ?: "tool", message.content, message.isError)
            // If the tool produced a file, show it inline below the result
            if (message.attachmentPath != null && message.attachmentMime != null) {
                Spacer(Modifier.height(4.dp))
                FileAttachmentBubble(
                    path = message.attachmentPath,
                    mime = message.attachmentMime,
                )
            }
        }
        "system"        -> ModernSystemBubble(message.content)
        "input_request" -> ModernInputRequestBubble(message.content)
        else            -> ModernAssistantBubble(message.content, message.isStreaming, onSpeak)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModernUserBubble(text: String) {
    var showSheet by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (showSheet) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "user_bubble_scale"
    )
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showSheet = true },
                ),
            color = Color.Transparent,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.linearGradient(
                        colors = listOf(
                            forgePalette.orange.copy(alpha = 0.25f),
                            forgePalette.orange.copy(alpha = 0.12f),
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
                )
            ) {
                SelectionContainer {
                    Text(
                        text,
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }

    if (showSheet) {
        BubbleActionsSheet(
            onDismiss = { showSheet = false },
            actions = listOf(
                BubbleAction("📋 Copy", Icons.Outlined.ContentCopy) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                }
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModernAssistantBubble(text: String, isStreaming: Boolean, onSpeak: (String) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val scale by animateFloatAsState(
        targetValue = if (showSheet) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "assistant_bubble_scale"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        ForgeLogo(size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .combinedClickable(
                    onClick = {},
                    onLongClick = { if (!isStreaming) showSheet = true },
                ),
            color = ModernSurface,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            val displayText = text + if (isStreaming) "▋" else ""
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    com.forge.os.presentation.screens.MarkdownText(
                        text = displayText,
                        baseColor = ModernTextPrimary,
                        baseFontSize = 14f
                    )
                }
            }
        }
    }

    if (showSheet) {
        BubbleActionsSheet(
            onDismiss = { showSheet = false },
            actions = listOf(
                BubbleAction("📋 Copy", Icons.Outlined.ContentCopy) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                },
                BubbleAction("🔊 Speak", Icons.Outlined.VolumeUp) {
                    onSpeak(text)
                }
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModernErrorBubble(msg: ChatMessage, onRetry: () -> Unit) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        ForgeLogo(size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showSheet = true },
                ),
            color = forgePalette.dangerBg,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(msg.content, color = forgePalette.danger, fontSize = 13.sp, lineHeight = 18.sp)
                    msg.errorDetail?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("provider=${err.provider} model=${err.model}")
                                if (err.httpCode > 0) append(" http=${err.httpCode}")
                                err.providerCode?.let { append(" code=$it") }
                            },
                            color = forgePalette.danger.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        BubbleActionsSheet(
            onDismiss = { showSheet = false },
            actions = listOf(
                BubbleAction("↺ Retry", Icons.Outlined.Refresh) { onRetry() },
                BubbleAction("📋 Copy", Icons.Outlined.ContentCopy) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(msg.content))
                }
            )
        )
    }
}

/** Compact chip shown while a tool is being called — gear icon + tool name + args preview. */
@Composable
private fun ModernToolCallChip(toolName: String, args: String) {
    var expanded by remember { mutableStateOf(false) }
    val PREVIEW = 120

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp),
        verticalAlignment = Alignment.Top
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "gear_spin")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "gear_rotation"
        )
        Icon(
            Icons.Filled.Settings, "Running",
            tint = ModernAccent,
            modifier = Modifier.size(14.dp).padding(top = 3.dp).graphicsLayer { rotationZ = rotation }
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = ModernAccent.copy(alpha = 0.08f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ModernAccent.copy(alpha = 0.25f)),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "⚙ $toolName",
                    color = ModernAccent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                if (args.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    val needsTruncation = args.length > PREVIEW
                    Text(
                        if (expanded || !needsTruncation) args
                        else args.take(PREVIEW) + "…",
                        color = ModernTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (needsTruncation) {
                        Text(
                            if (expanded) "▲ less" else "▼ more",
                            color = ModernAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Result bubble shown after a tool completes — tick/cross + tool name + output preview. */
@Composable
private fun ModernToolResultBubble(toolName: String, result: String, isError: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val PREVIEW = 300

    val accentColor = if (isError) forgePalette.danger else forgePalette.success
    val bgColor = if (isError) forgePalette.dangerBg else forgePalette.successBg
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (isError) Icons.Filled.Close else Icons.Filled.Check,
            if (isError) "Error" else "Done",
            tint = accentColor,
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.25f)),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    toolName,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                if (result.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    val needsTruncation = result.length > PREVIEW
                    Text(
                        if (expanded || !needsTruncation) result
                        else result.take(PREVIEW) + "…",
                        color = ModernTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                    if (needsTruncation) {
                        Text(
                            if (expanded) "▲ show less" else "▼ show more",
                            color = accentColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernSystemBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(forgePalette.infoBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = forgePalette.info, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    }
}

@Composable
private fun ModernInputRequestBubble(question: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(forgePalette.successBg, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("❓", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(question, color = forgePalette.success, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
    }
}

/** Small pill button shown in the long-press action row under a bubble. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleActionsSheet(
    onDismiss: () -> Unit,
    actions: List<BubbleAction>,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ModernSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(ModernBorder, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            action.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = ModernAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        action.label,
                        color = ModernTextPrimary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private data class BubbleAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun BubbleActionButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder),
    ) {
        Text(
            label,
            color = ModernTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Inline file attachment card shown below a tool_result bubble.
 * - Images: rendered inline with a tap-to-open action
 * - Audio: play/pause button using MediaPlayer
 * - Everything else: filename + open/share button
 */
@Composable
private fun FileAttachmentBubble(path: String, mime: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = remember(path) { java.io.File(path) }
    if (!file.exists()) return

    val isImage = mime.startsWith("image/")
    val isAudio = mime.startsWith("audio/")

    Column(
        modifier = Modifier
            .padding(start = 44.dp)
            .widthIn(max = 520.dp)
    ) {
        when {
            isImage -> {
                // Inline image preview — tap to open full-screen
                coil.compose.AsyncImage(
                    model = path,
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { openFile(context, file, mime) },
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(4.dp))
                FileActionRow(file, mime, context)
            }
            isAudio -> {
                AudioPlayerCard(file, context)
            }
            else -> {
                FileCard(file, mime, context)
            }
        }
    }
}

@Composable
private fun AudioPlayerCard(file: java.io.File, context: android.content.Context) {
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { android.media.MediaPlayer() }

    DisposableEffect(file.absolutePath) {
        onDispose {
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.release()
        }
    }

    Surface(
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        runCatching {
                            if (!mediaPlayer.isPlaying) {
                                mediaPlayer.reset()
                                mediaPlayer.setDataSource(file.absolutePath)
                                mediaPlayer.prepare()
                                mediaPlayer.setOnCompletionListener { isPlaying = false }
                            }
                            mediaPlayer.start()
                            isPlaying = true
                        }
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(ModernAccent, CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    color = ModernTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    formatFileSize(file.length()),
                    color = ModernTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = { shareFile(context, file) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Share, "Share", tint = ModernTextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FileCard(file: java.io.File, mime: String, context: android.content.Context) {
    Surface(
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ModernAccent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    file.extension.uppercase().take(4),
                    color = ModernAccent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    color = ModernTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    formatFileSize(file.length()),
                    color = ModernTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { openFile(context, file, mime) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.OpenInNew, "Open", tint = ModernAccent, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { shareFile(context, file) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Share, "Share", tint = ModernTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FileActionRow(file: java.io.File, mime: String, context: android.content.Context) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BubbleActionButton("Open") { openFile(context, file, mime) }
        BubbleActionButton("Share") { shareFile(context, file) }
    }
}

private fun openFile(context: android.content.Context, file: java.io.File, mime: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun shareFile(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${file.name}").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

@Composable
private fun TypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Agent avatar with actual logo
        ForgeLogo(size = 32.dp)
        
        Surface(
            color = ModernSurface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(3) { index ->
                    val infiniteTransition = rememberInfiniteTransition(label = "dot_$index")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = 600,
                                delayMillis = index * 200,
                                easing = FastOutSlowInEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_alpha_$index"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                ModernAccent.copy(alpha = alpha),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceMode: () -> Unit,
    onClearChat: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean
) {
    // Unified pill composer — +, field, send all in one rounded container
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = ModernSurface,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Plus button — attachments / actions
            var showPlusMenu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { showPlusMenu = true },
                    color = ModernSurfaceHover,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Add,
                            "Attach",
                            tint = ModernTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = showPlusMenu,
                    onDismissRequest = { showPlusMenu = false },
                    modifier = Modifier.background(ModernSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear chat", color = ModernTextPrimary, fontSize = 14.sp) },
                        onClick = { showPlusMenu = false; onClearChat() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ModernTextSecondary, modifier = Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Voice mode", color = ModernTextPrimary, fontSize = 14.sp) },
                        onClick = { showPlusMenu = false; onVoiceMode() },
                        leadingIcon = { Icon(Icons.Filled.Mic, null, tint = ModernTextSecondary, modifier = Modifier.size(20.dp)) }
                    )
                }
            }

            // Text field — transparent, fills available space
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp, max = 160.dp)
                    .then(com.forge.os.presentation.components.spotlightTarget("input_field")),
                placeholder = {
                    Text(
                        "Message Forge...",
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
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = ModernTextPrimary,
                    unfocusedTextColor = ModernTextPrimary,
                    cursorColor = ModernAccent
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Default,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                ),
            )

            // Voice input button (inline mic)
            com.forge.os.presentation.screens.voice.VoiceInputButton(
                onVoiceInput = { recognizedText -> onValueChange(recognizedText) },
                modifier = Modifier.size(44.dp)
            )

            // Send/Stop button — ember circle when active with press animation
            val sendEnabled = value.isNotBlank() && enabled
            var sendPressed by remember { mutableStateOf(false) }
            val sendScale by animateFloatAsState(
                targetValue = if (sendPressed) 0.85f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh
                ),
                label = "sendScale"
            )
            
            if (isLoading) {
                // Stop button when generating
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .scale(sendScale)
                        .clip(CircleShape)
                        .clickable {
                            sendPressed = true
                            onStop()
                        },
                    color = forgePalette.danger,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Stop,
                            "Stop",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                // Send button
                val sendBgColor by animateColorAsState(
                    targetValue = if (sendEnabled) ModernAccent else ModernSurfaceHover,
                    animationSpec = tween(200),
                    label = "sendBg"
                )
                val sendIconColor by animateColorAsState(
                    targetValue = if (sendEnabled) forgePalette.onAccent else ModernTextSecondary,
                    animationSpec = tween(200),
                    label = "sendIcon"
                )
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .scale(sendScale)
                        .clip(CircleShape)
                        .clickable(enabled = sendEnabled) {
                            sendPressed = true
                            onSend()
                        }
                        .then(com.forge.os.presentation.components.spotlightTarget("send_button")),
                    color = sendBgColor,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            "Send",
                            tint = sendIconColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputRequestCard(
    question: String,
    onSubmit: (String) -> Unit
) {
    var response by remember { mutableStateOf("") }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = ModernAccent.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ModernAccent.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Help,
                    "Question",
                    tint = ModernAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Agent needs information",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Text(
                question,
                color = ModernTextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Your answer...", color = ModernTextSecondary, fontSize = 13.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ModernAccent,
                        unfocusedBorderColor = ModernBorder,
                        focusedTextColor = ModernTextPrimary,
                        unfocusedTextColor = ModernTextPrimary,
                        cursorColor = ModernAccent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                
                IconButton(
                    onClick = {
                        if (response.isNotBlank()) {
                            onSubmit(response)
                            response = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(ModernAccent, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.Filled.Send,
                        "Submit",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernSideMenu(
    currentRoute: String,
    onDismiss: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToHub: () -> Unit,
    onNavigateToCompanion: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .clickable(enabled = false) { },
            color = forgePalette.bg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                com.forge.os.presentation.components.DrawerHeader()

                Spacer(Modifier.height(8.dp))

                // Menu items
                com.forge.os.presentation.components.DrawerSection("WORKSPACE")
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Chat, "Chat",
                    isActive = currentRoute == "chat",
                    onClick = onDismiss
                )
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Folder, "Files",
                    isActive = currentRoute == "workspace",
                    onClick = onNavigateToWorkspace
                )
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Apps, "Hub",
                    isActive = currentRoute == "hub",
                    onClick = onNavigateToHub
                )

                Spacer(Modifier.height(8.dp))

                com.forge.os.presentation.components.DrawerSection("TOOLS")
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.MonitorHeart, "Status",
                    isActive = currentRoute == "status",
                    onClick = onNavigateToStatus
                )
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Language, "Browser",
                    isActive = currentRoute == "browser",
                    onClick = onNavigateToBrowser
                )

                Spacer(Modifier.height(8.dp))

                com.forge.os.presentation.components.DrawerSection("HISTORY")
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.History, "Conversations",
                    isActive = currentRoute == "conversations",
                    onClick = onNavigateToConversations
                )
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Favorite, "Companion",
                    isActive = currentRoute == "companion",
                    onClick = onNavigateToCompanion
                )

                Spacer(Modifier.weight(1f))

                HorizontalDivider(
                    color = forgePalette.divider,
                    thickness = 0.5.dp
                )
                com.forge.os.presentation.components.DrawerItem(
                    Icons.Outlined.Settings, "Settings",
                    isActive = currentRoute == "settings",
                    onClick = onNavigateToSettings
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}