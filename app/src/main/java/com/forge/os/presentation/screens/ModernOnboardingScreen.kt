package com.forge.os.presentation.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.presentation.components.*

/**
 * Modern onboarding screen with ChatGPT/Claude-inspired design.
 * Features:
 * - Animated logo entrance
 * - Smooth page transitions
 * - Feature highlights with icons
 * - Gradient accents
 * - Clear CTAs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernOnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var page by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModernBg)
    ) {
        // Animated gradient background
        AnimatedGradientBackground()
        
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Modern Header with progress
            ModernOnboardingHeader(
                currentPage = page,
                totalPages = 3
            )
            
            // Content area with page transitions
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        slideInHorizontally { it } + fadeIn() togetherWith
                        slideOutHorizontally { -it } + fadeOut()
                    },
                    label = "page_transition"
                ) { currentPage ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        when (currentPage) {
                            0 -> ModernWelcomePage()
                            1 -> ModernCapabilitiesPage()
                            2 -> ModernApiKeyPage(
                                provider = state.provider,
                                apiKey = state.apiKey,
                                error = state.error,
                                onProviderChange = viewModel::selectProvider,
                                onKeyChange = viewModel::updateKey
                            )
                        }
                    }
                }
            }
            
            // Modern bottom navigation
            ModernOnboardingBottomBar(
                currentPage = page,
                canFinish = state.canFinish,
                isBusy = state.busy,
                onBack = { if (page > 0) page-- },
                onNext = { if (page < 2) page++ },
                onFinish = {
                    viewModel.finish {
                        onDone()
                    }
                }
            )
        }
    }
}

@Composable
private fun ModernOnboardingHeader(
    currentPage: Int,
    totalPages: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Logo and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ForgeLogo(size = 40.dp, animated = true)
                
                Column {
                    Text(
                        "Forge OS",
                        color = ModernTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "AI Development Environment",
                        color = ModernTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Progress indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(totalPages) { index ->
                    val isActive = index <= currentPage
                    val progress = when {
                        index < currentPage -> 1f
                        index == currentPage -> 1f
                        else -> 0f
                    }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                if (isActive) ModernAccent else ModernBorder,
                                RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animateFloatAsState(progress, label = "progress").value)
                                .background(ModernAccent, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernWelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        
        // Animated logo
        val scale by rememberInfiniteTransition(label = "logo").animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
        ) {
            ForgeLogo(size = 120.dp)
        }
        
        Text(
            "Welcome to Forge OS",
            color = ModernTextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            "An on-device AI agent operating system that runs locally, uses your API key, and gives an LLM agent a sandboxed workspace.",
            color = ModernTextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Privacy card
        ModernCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = ModernSuccess,
                    modifier = Modifier.size(24.dp)
                )
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Privacy First",
                        color = ModernTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    PrivacyPoint("Your API key is stored encrypted on this device only")
                    PrivacyPoint("Conversations & files never leave your device")
                    PrivacyPoint("Tools run in a sandboxed environment")
                }
            }
        }
        
        // Feature highlights
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeatureHighlight(
                icon = Icons.Outlined.Memory,
                title = "Smart Memory",
                modifier = Modifier.weight(1f)
            )
            FeatureHighlight(
                icon = Icons.Outlined.Code,
                title = "Code Execution",
                modifier = Modifier.weight(1f)
            )
            FeatureHighlight(
                icon = Icons.Outlined.Extension,
                title = "Plugins",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .offset(y = 6.dp)
                .background(ModernSuccess, CircleShape)
        )
        Text(
            text,
            color = ModernTextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun FeatureHighlight(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = ModernSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = ModernAccent,
                modifier = Modifier.size(28.dp)
            )
            Text(
                title,
                color = ModernTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModernCapabilitiesPage() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "What Forge Can Do",
            color = ModernTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            "A comprehensive AI development environment with powerful capabilities",
            color = ModernTextSecondary,
            fontSize = 14.sp
        )
        
        Spacer(Modifier.height(8.dp))
        
        val capabilities = listOf(
            Triple(Icons.Outlined.Memory, "Memory", "Three-tier memory system with semantic embeddings for context across sessions"),
            Triple(Icons.Outlined.Folder, "Workspace", "Sandboxed file system with full file manager UI and device integration"),
            Triple(Icons.Outlined.Code, "Code Execution", "Run Python and shell commands with timeouts and captured output"),
            Triple(Icons.Outlined.Schedule, "Scheduling", "Cron jobs and alarms for recurring tasks and notifications"),
            Triple(Icons.Outlined.Language, "Browser", "Headless and on-screen browsers with viewport control and screenshots"),
            Triple(Icons.Outlined.Extension, "Plugins", "Install Python plugins that extend the agent's capabilities"),
            Triple(Icons.Outlined.Hub, "MCP Client", "Connect to Model Context Protocol servers for external tools"),
            Triple(Icons.Outlined.SmartToy, "Sub-agents", "Spawn focused sub-agents to parallelize work"),
            Triple(Icons.Outlined.Favorite, "Companion", "Warmer conversation mode with persona and episodic memory"),
            Triple(Icons.Outlined.CameraAlt, "Snapshots", "Time-travel your workspace with snapshot and restore"),
            Triple(Icons.Outlined.Api, "External API", "Other apps can call Forge as an on-device LLM service"),
            Triple(Icons.Outlined.AttachMoney, "Cost Tracking", "Live token and USD spend tracking per call and session")
        )
        
        capabilities.forEach { (icon, title, description) ->
            CapabilityCard(icon, title, description)
        }
    }
}

@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    ModernCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        ModernAccent.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
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
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    description,
                    color = ModernTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernApiKeyPage(
    provider: ApiKeyProvider,
    apiKey: String,
    error: String?,
    onProviderChange: (ApiKeyProvider) -> Unit,
    onKeyChange: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Connect Your LLM",
                color = ModernTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                "Forge uses your API key directly — bring your own key from any supported provider. You can change this later in Settings.",
                color = ModernTextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        
        // Provider selector
        ModernCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Select Provider",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                ExposedDropdownMenuBox(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = !menuOpen }
                ) {
                    OutlinedTextField(
                        value = provider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider", fontSize = 13.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ModernAccent,
                            unfocusedBorderColor = ModernBorder,
                            focusedTextColor = ModernTextPrimary,
                            unfocusedTextColor = ModernTextPrimary,
                            focusedLabelColor = ModernAccent,
                            unfocusedLabelColor = ModernTextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        ApiKeyProvider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName) },
                                onClick = { onProviderChange(p); menuOpen = false }
                            )
                        }
                    }
                }
            }
        }
        
        // API key input
        ModernCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "API Key",
                    color = ModernTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onKeyChange,
                    label = { Text("Enter your API key", fontSize = 13.sp) },
                    placeholder = { Text("sk-...", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showKey) "Hide" else "Show",
                                tint = ModernTextSecondary
                            )
                        }
                    },
                    isError = error != null,
                    supportingText = error?.let { 
                        { Text(it, color = ModernError, fontSize = 12.sp) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (error != null) ModernError else ModernAccent,
                        unfocusedBorderColor = if (error != null) ModernError else ModernBorder,
                        focusedTextColor = ModernTextPrimary,
                        unfocusedTextColor = ModernTextPrimary,
                        focusedLabelColor = if (error != null) ModernError else ModernAccent,
                        unfocusedLabelColor = ModernTextSecondary,
                        cursorColor = ModernAccent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        
        // Info card
        Surface(
            color = ModernAccent.copy(alpha = 0.1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = ModernAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Don't have a key? You can also point Forge at a local Ollama server in Settings — no key required.",
                    color = ModernTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ModernOnboardingBottomBar(
    currentPage: Int,
    canFinish: Boolean,
    isBusy: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModernSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            if (currentPage > 0) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ModernTextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Back", fontSize = 14.sp)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            
            // Next/Finish button
            Button(
                onClick = if (currentPage < 2) onNext else onFinish,
                enabled = if (currentPage < 2) true else (canFinish && !isBusy),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModernAccent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBusy && currentPage == 2) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving...", fontSize = 14.sp)
                } else {
                    Text(
                        if (currentPage < 2) "Next" else "Get Started",
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (currentPage < 2) Icons.Outlined.ArrowForward else Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
