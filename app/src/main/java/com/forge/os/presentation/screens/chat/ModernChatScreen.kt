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
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showVoiceMode by remember { mutableStateOf(false) }
    
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
                onSettingsClick = onNavigateToSettings,
                isLoading = isLoading,
                selectedSpec = selectedSpec,
                availableSpecs = availableSpecs,
                onSelectSpec = { viewModel.selectSpec(it) },
                onVoiceModeClick = { showVoiceMode = true },
            )
            
            // Messages Area
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ModernMessageBubble(
                                message = msg,
                                onRetry = { viewModel.retryLast() },
                                onSpeak = { text -> voiceVm.speak(text) },
                            )
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
                onVoiceMode = { showVoiceMode = true },
                enabled = !isLoading
            )
        }
        
        // Floating Action Buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.clearMessages() },
                containerColor = ModernSurface,
                contentColor = ModernTextPrimary,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Outlined.Delete, "Clear", modifier = Modifier.size(20.dp))
            }
            
            FloatingActionButton(
                onClick = onNavigateToBrowser,
                containerColor = ModernSurface,
                contentColor = ModernTextPrimary,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Outlined.Language, "Browser", modifier = Modifier.size(20.dp))
            }
        }
        
        // Side Menu
        if (showMenu) {
            ModernSideMenu(
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
    }
}

@Composable
private fun ModernHeader(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isLoading: Boolean,
    selectedSpec: com.forge.os.domain.security.ProviderSpec?,
    availableSpecs: List<com.forge.os.domain.security.ProviderSpec>,
    onSelectSpec: (com.forge.os.domain.security.ProviderSpec) -> Unit,
    onVoiceModeClick: () -> Unit,
) {
    var showModelMenu by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = ModernSurface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Menu button
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Menu,
                    "Menu",
                    tint = ModernTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Logo and title
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
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isLoading) {
                        Text(
                            "Thinking...",
                            color = ModernTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            // Model selector
            Box {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showModelMenu = true },
                    color = ModernSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedSpec?.displayLabel ?: "Auto",
                            color = ModernTextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = ModernTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false },
                    modifier = Modifier
                        .background(ModernSurface)
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
            
            Spacer(Modifier.width(8.dp))
            
            // Voice mode button
            IconButton(
                onClick = onVoiceModeClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.Mic,
                    "Voice Mode",
                    tint = ModernAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Settings button
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    "Settings",
                    tint = ModernTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated logo
            val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                ForgeLogo(size = 80.dp, animated = false)
            }
            
            Text(
                "How can I help you today?",
                color = ModernTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            
            Text(
                "Ask me anything or use / for commands",
                color = ModernTextSecondary,
                fontSize = 14.sp
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Quick actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionChip(
                    icon = Icons.Outlined.Code,
                    label = "Code Review"
                )
                QuickActionChip(
                    icon = Icons.Outlined.Folder,
                    label = "Projects"
                )
                QuickActionChip(
                    icon = Icons.Outlined.Memory,
                    label = "Memory"
                )
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        color = ModernSurface,
        onClick = { /* TODO: Handle quick action */ }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = ModernAccent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                label,
                color = ModernTextPrimary,
                fontSize = 13.sp
            )
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
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showSheet = true },
                    ),
                color = ModernAccent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
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
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier.size(32.dp).background(ModernSurface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Person, "User", tint = ModernTextPrimary, modifier = Modifier.size(18.dp))
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
            color = Color(0xFF1a0a0a),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(msg.content, color = Color(0xFFef4444), fontSize = 13.sp, lineHeight = 18.sp)
                    msg.errorDetail?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("provider=${err.provider} model=${err.model}")
                                if (err.httpCode > 0) append(" http=${err.httpCode}")
                                err.providerCode?.let { append(" code=$it") }
                            },
                            color = Color(0xFF7f1d1d), fontSize = 10.sp, fontFamily = FontFamily.Monospace
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

    val accentColor = if (isError) Color(0xFFef4444) else Color(0xFF22c55e)
    val bgColor = if (isError) Color(0xFF1a0a0a) else Color(0xFF0a1a0a)
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
            .background(Color(0xFF0f172a), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color(0xFF94a3b8), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    }
}

@Composable
private fun ModernInputRequestBubble(question: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0c1a0a), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("❓", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(question, color = Color(0xFF86efac), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
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
    onVoiceMode: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Input field — grows with content, capped at ~5 lines
            Surface(
                modifier = Modifier.weight(1f),
                color = ModernBg,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 160.dp),
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
                            imeAction = ImeAction.Default,   // allow newlines
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        ),
                    )

                    // Voice input button
                    com.forge.os.presentation.screens.voice.VoiceInputButton(
                        onVoiceInput = { recognizedText -> onValueChange(recognizedText) },
                        modifier = Modifier
                            .size(36.dp)
                            .padding(bottom = 2.dp)
                    )
                }
            }

            // Send button
            FloatingActionButton(
                onClick = onSend,
                containerColor = if (value.isNotBlank() && enabled) ModernAccent else ModernSurface,
                contentColor = Color.White,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (value.isNotBlank()) Icons.Filled.Send else Icons.Outlined.Send,
                    "Send",
                    modifier = Modifier.size(22.dp)
                )
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
            color = ModernSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ForgeLogo(size = 40.dp)
                    
                    Column {
                        Text(
                            "Forge OS",
                            color = ModernTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "AI Development",
                            color = ModernTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                
                // Menu items
                MenuSection("WORKSPACE")
                MenuItem(Icons.Outlined.Folder, "Files", onNavigateToWorkspace)
                MenuItem(Icons.Outlined.Apps, "Hub", onNavigateToHub)
                
                Spacer(Modifier.height(16.dp))
                
                MenuSection("TOOLS")
                MenuItem(Icons.Outlined.MonitorHeart, "Status", onNavigateToStatus)
                MenuItem(Icons.Outlined.Language, "Browser", onNavigateToBrowser)
                
                Spacer(Modifier.height(16.dp))
                
                MenuSection("HISTORY")
                MenuItem(Icons.Outlined.History, "Conversations", onNavigateToConversations)
                MenuItem(Icons.Outlined.Chat, "Companion", onNavigateToCompanion)
                
                Spacer(Modifier.weight(1f))
                
                MenuItem(Icons.Outlined.Settings, "Settings", onNavigateToSettings)
            }
        }
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(
        title,
        color = ModernTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = ModernTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                color = ModernTextPrimary,
                fontSize = 14.sp
            )
        }
    }
}