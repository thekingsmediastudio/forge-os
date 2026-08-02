package com.forge.os.presentation.screens.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.channel.Channel
import com.forge.os.domain.channel.ChannelType
import com.forge.os.presentation.components.spotlightTarget
import com.forge.os.presentation.screens.channels.ChannelViewModel
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

/**
 * Memories screen - manage memory channels for scoped AI conversations.
 */
@Composable
fun MemoriesScreen(
    onBack: () -> Unit,
    viewModel: ChannelViewModel = hiltViewModel()
) {
    val channels by viewModel.channels.collectAsState()
    val currentChannel by viewModel.currentChannel.collectAsState()
    val channelsEnabled by viewModel.channelsEnabled.collectAsState()
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Channel?>(null) }
    var deleteTarget by remember { mutableStateOf<Channel?>(null) }
    
    // Tutorial state
    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
    val tutorialManager = tutorialVm.tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowTutorial(com.forge.os.domain.tutorial.TutorialType.WORKSPACE)) {
            kotlinx.coroutines.delay(500)
            showTutorial = true
        }
    }

    ModuleScaffold(
        title = "MEMORIES",
        onBack = onBack,
        actions = {
            Box(modifier = Modifier.spotlightTarget("memories_add")) {
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Outlined.Add, null, tint = ForgeOsPalette.Orange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("NEW", color = ForgeOsPalette.Orange, fontSize = 12.sp)
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Enable/Disable toggle
            Box(modifier = Modifier.spotlightTarget("memories_toggle")) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ForgeOsPalette.Surface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🧠", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Memory Channels",
                                color = ForgeOsPalette.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (channelsEnabled) "Channels are active" else "Enable to separate AI memory",
                                color = ForgeOsPalette.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = channelsEnabled,
                            onCheckedChange = { viewModel.setChannelsEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = ForgeOsPalette.Orange,
                                checkedThumbColor = Color.White
                            )
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(20.dp))
            
            // Current channel indicator
            if (channelsEnabled) {
                Text(
                    "ACTIVE CHANNEL",
                    color = ForgeOsPalette.TextMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ForgeOsPalette.Orange.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(currentChannel.icon, fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                currentChannel.name,
                                color = ForgeOsPalette.Orange,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "All new conversations go here",
                                color = ForgeOsPalette.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Text("●", color = ForgeOsPalette.Orange, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            
            // Channel list
            Text(
                "ALL CHANNELS (${channels.size + 1})",
                color = ForgeOsPalette.TextMuted,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(8.dp))
            
            Box(modifier = Modifier.spotlightTarget("memories_list")) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Default General channel
                    item {
                        ChannelCard(
                            channel = Channel.GENERAL,
                            isActive = currentChannel.id == Channel.GENERAL.id,
                            isDefault = true,
                            onSelect = { viewModel.switchChannel(Channel.GENERAL.id) },
                            onEdit = null,
                            onDelete = null
                        )
                    }
                    
                    // User channels
                    items(channels, key = { it.id }) { channel ->
                        ChannelCard(
                            channel = channel,
                            isActive = currentChannel.id == channel.id,
                            isDefault = false,
                            onSelect = { viewModel.switchChannel(channel.id) },
                            onEdit = { editTarget = channel },
                            onDelete = { deleteTarget = channel }
                        )
                    }
                }
            }
            
            if (channels.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Create channels to organize your AI memory by context.\nExamples: Work, Personal, Projects",
                    color = ForgeOsPalette.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
    
    // Tutorial Overlay
    if (showTutorial) {
        val tutorialSteps = listOf(
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Memory Channels",
                description = "Separate your AI conversations by context. Each channel has its own memory.",
                targetKey = null,
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Enable Channels",
                description = "Toggle this to activate memory channels. When off, all chats use General.",
                targetKey = "memories_toggle",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Create Channels",
                description = "Tap + NEW to create custom channels like Work, Personal, or Projects.",
                targetKey = "memories_add",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Switch Channels",
                description = "Tap any channel to make it active. New conversations will use that channel's memory.",
                targetKey = "memories_list",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
            )
        )
        
        com.forge.os.presentation.components.CoachMarkOverlay(
            steps = tutorialSteps,
            currentStep = tutorialStep,
            onNext = { tutorialStep++ },
            onSkip = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.WORKSPACE)
            },
            onDone = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.WORKSPACE)
            }
        )
    }
    
    // Create Dialog
    if (showCreateDialog) {
        ChannelEditDialog(
            title = "Create Channel",
            initialName = "",
            initialIcon = "💬",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, icon ->
                viewModel.createChannel(name, icon)
                showCreateDialog = false
            }
        )
    }
    
    // Edit Dialog
    editTarget?.let { channel ->
        ChannelEditDialog(
            title = "Edit Channel",
            initialName = channel.name,
            initialIcon = channel.icon,
            onDismiss = { editTarget = null },
            onConfirm = { name, icon ->
                viewModel.updateChannel(channel.copy(name = name, icon = icon))
                editTarget = null
            }
        )
    }
    
    // Delete Dialog
    deleteTarget?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${channel.name}\"?") },
            text = { Text("This will delete the channel. Conversations in this channel will be moved to General.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChannel(channel.id)
                        deleteTarget = null
                    }
                ) { Text("DELETE", color = ForgeOsPalette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    isActive: Boolean,
    isDefault: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val borderColor = if (isActive) ForgeOsPalette.Orange else ForgeOsPalette.Border
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        color = if (isActive) ForgeOsPalette.Orange.copy(alpha = 0.05f) else ForgeOsPalette.Surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        ForgeOsPalette.SurfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(channel.icon, fontSize = 20.sp)
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Name & info
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        color = ForgeOsPalette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = ForgeOsPalette.TextMuted.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "DEFAULT",
                                color = ForgeOsPalette.TextMuted,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    if (isActive) "Active" else "Tap to switch",
                    color = if (isActive) ForgeOsPalette.Orange else ForgeOsPalette.TextMuted,
                    fontSize = 12.sp
                )
            }
            
            // Active indicator
            if (isActive) {
                Text("●", color = ForgeOsPalette.Orange, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
            }
            
            // Actions
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        "Edit",
                        tint = ForgeOsPalette.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        "Delete",
                        tint = ForgeOsPalette.Danger,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelEditDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedIcon by remember { mutableStateOf(initialIcon) }
    
    val iconOptions = listOf("💬", "💼", "🏠", "🚀", "❤️", "✨", "📚", "🎮", "🎨", "🔧", "📊", "🌱")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(16.dp))
                
                Text("Icon", fontSize = 13.sp, color = ForgeOsPalette.TextMuted)
                Spacer(Modifier.height(8.dp))
                
                // Icon picker grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    iconOptions.take(6).forEach { icon ->
                        IconOption(
                            icon = icon,
                            isSelected = icon == selectedIcon,
                            onClick = { selectedIcon = icon }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    iconOptions.drop(6).forEach { icon ->
                        IconOption(
                            icon = icon,
                            isSelected = icon == selectedIcon,
                            onClick = { selectedIcon = icon }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedIcon) },
                enabled = name.isNotBlank()
            ) { Text("SAVE", color = ForgeOsPalette.Orange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
private fun IconOption(
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                if (isSelected) ForgeOsPalette.Orange.copy(alpha = 0.2f) else ForgeOsPalette.SurfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (isSelected) ForgeOsPalette.Orange else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 20.sp)
    }
}
