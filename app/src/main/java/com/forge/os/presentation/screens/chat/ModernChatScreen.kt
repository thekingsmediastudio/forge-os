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
import androidx.compose.ui.draw.graphicsLayer
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
import com.forge.os.data.conversations.Message
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
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    
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
                isLoading = isLoading
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
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + slideInVertically { it / 4 },
                                exit = fadeOut()
                            ) {
                                ModernMessageBubble(msg)
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
    }
}

@Composable
private fun ModernHeader(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
private fun ModernMessageBubble(message: Message) {
    val isUser = message.role == "user"
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Agent avatar with actual logo
            ForgeLogo(size = 32.dp)
            
            Spacer(Modifier.width(12.dp))
        }
        
        Surface(
            modifier = Modifier.widthIn(max = 600.dp),
            color = if (isUser) ModernAccent.copy(alpha = 0.15f) else ModernSurface,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    message.content,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = if (message.content.contains("```")) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
        
        if (isUser) {
            Spacer(Modifier.width(12.dp))
            
            // User avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(ModernSurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Person,
                    "User",
                    tint = ModernTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
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
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Input field
            Surface(
                modifier = Modifier.weight(1f),
                color = ModernBg,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ModernBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() })
                    )
                    
                    // Voice input button
                    com.forge.os.presentation.screens.voice.VoiceInputButton(
                        onVoiceInput = { recognizedText ->
                            onValueChange(recognizedText)
                        },
                        modifier = Modifier.size(40.dp)
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