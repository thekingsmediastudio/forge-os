package com.forge.os.presentation.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
                            ModernMessageBubble(msg)
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
            }
        }
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
private fun ModernMessageBubble(message: ChatMessage) {
    when (message.role) {
        "user"          -> ModernUserBubble(message.content)
        "assistant"     -> if (message.isError) ModernErrorBubble(message)
                           else ModernAssistantBubble(message.content, message.isStreaming)
        "tool_call"     -> ModernToolCallChip(message.toolName ?: "tool", message.content)
        "tool_result"   -> ModernToolResultBubble(message.toolName ?: "tool", message.content, message.isError)
        "system"        -> ModernSystemBubble(message.content)
        "input_request" -> ModernInputRequestBubble(message.content)
        else            -> ModernAssistantBubble(message.content, message.isStreaming)
    }
}

@Composable
private fun ModernUserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            color = ModernAccent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            Text(
                text,
                color = ModernTextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier.size(32.dp).background(ModernSurface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Person, "User", tint = ModernTextPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ModernAssistantBubble(text: String, isStreaming: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        ForgeLogo(size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Surface(
            modifier = Modifier.widthIn(max = 600.dp),
            color = ModernSurface,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            val displayText = text + if (isStreaming) "▋" else ""
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (displayText.contains("**") || displayText.contains("`") ||
                    displayText.contains("```") || displayText.contains("# ") ||
                    displayText.contains("- ") || displayText.contains("> ")) {
                    com.forge.os.presentation.screens.MarkdownText(
                        text = displayText,
                        baseColor = ModernTextPrimary,
                        baseFontSize = 14f
                    )
                } else {
                    Text(
                        displayText,
                        color = ModernTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernErrorBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        ForgeLogo(size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            color = Color(0xFF1a0a0a),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
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

/** Compact chip shown while a tool is being called — gear icon + tool name + args preview. */
@Composable
private fun ModernToolCallChip(toolName: String, args: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Spinning gear while tool runs
        val infiniteTransition = rememberInfiniteTransition(label = "gear_spin")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "gear_rotation"
        )
        Icon(
            Icons.Filled.Settings, "Running",
            tint = ModernAccent,
            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation }
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = ModernAccent.copy(alpha = 0.08f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ModernAccent.copy(alpha = 0.25f))
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
                    Text(
                        args.take(120).let { if (args.length > 120) "$it…" else it },
                        color = ModernTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** Result bubble shown after a tool completes — tick/cross + tool name + output preview. */
@Composable
private fun ModernToolResultBubble(toolName: String, result: String, isError: Boolean) {
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
                    Text(
                        result.take(300).let { if (result.length > 300) "$it…" else it },
                        color = ModernTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
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
    enabled: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        color = ModernSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Input field
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                color = ModernBg,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
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
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() })
                    )
                    
                    // Voice input button
                    com.forge.os.presentation.screens.voice.VoiceInputButton(
                        onVoiceInput = { recognizedText ->
                            onValueChange(recognizedText)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            // Send button
            FloatingActionButton(
                onClick = onSend,
                containerColor = if (value.isNotBlank() && enabled) {
                    ModernAccent
                } else {
                    ModernSurface
                },
                contentColor = Color.White,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (value.isNotBlank()) Icons.Filled.Send else Icons.Outlined.Send,
                    "Send",
                    modifier = Modifier.size(24.dp)
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