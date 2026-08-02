package com.forge.os.presentation.screens.conversations

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.data.conversations.StoredConversation
import com.forge.os.presentation.components.spotlightTarget
import com.forge.os.presentation.theme.forgePalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(
    onBack: () -> Unit,
    onOpened: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var renameTarget: StoredConversation? by remember { mutableStateOf(null) }
    var deleteTarget: StoredConversation? by remember { mutableStateOf(null) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // Tutorial state
    val tutorialVm: com.forge.os.presentation.screens.chat.TutorialViewModel = hiltViewModel()
    val tutorialManager = tutorialVm.tutorialManager
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (tutorialManager.shouldShowTutorial(com.forge.os.domain.tutorial.TutorialType.CONVERSATIONS)) {
            kotlinx.coroutines.delay(500)
            showTutorial = true
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    val filtered = remember(state.items, searchQuery) {
        if (searchQuery.isBlank()) state.items
        else state.items.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.messages.lastOrNull()?.content?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(forgePalette.bg)
            .statusBarsPadding()
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = forgePalette.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "Conversations",
                color = forgePalette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.spotlightTarget("conversations_new")) {
                IconButton(
                    onClick = { viewModel.startNew(); onOpened() },
                    modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(forgePalette.orange.copy(alpha = 0.15f))
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "New conversation",
                        tint = forgePalette.orange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Search bar ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(forgePalette.surface)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = forgePalette.textMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                        color = forgePalette.textPrimary,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(forgePalette.orange),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search conversations…",
                                color = forgePalette.textMuted.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = forgePalette.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // ── Channel filter indicator ──────────────────────────────────────
        if (state.channelsEnabled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = forgePalette.orange.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🧠", fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Showing: ${state.currentChannelName}",
                        color = forgePalette.orange,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ── Count label ───────────────────────────────────────────────────
        Text(
            "${filtered.size} conversation${if (filtered.size != 1) "s" else ""}",
            color = forgePalette.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ── List ──────────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = forgePalette.textMuted.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (searchQuery.isNotBlank()) "No matches found"
                    else "No conversations yet",
                    color = forgePalette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (searchQuery.isNotBlank()) "Try a different search term"
                    else "Start a new conversation to get going",
                    color = forgePalette.textMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .spotlightTarget("conversations_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { conv ->
                    ConversationCard(
                        conv = conv,
                        isCurrent = conv.id == state.currentId,
                        onOpen = { viewModel.switchTo(conv.id); onOpened() },
                        onRename = { renameTarget = conv },
                        onDelete = { deleteTarget = conv })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        SnackbarHost(snackbar)
    }

    // ── Tutorial Overlay ──────────────────────────────────────────────────
    if (showTutorial) {
        val tutorialSteps = listOf(
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Conversations",
                description = "All your chat history is saved here. Tap any conversation to continue where you left off.",
                targetKey = null,
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "New Conversation",
                description = "Start a fresh conversation with the + button.",
                targetKey = "conversations_new",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.BOTTOM
            ),
            com.forge.os.presentation.components.CoachMarkStep(
                title = "Manage Chats",
                description = "Long-press a conversation to rename or delete it.",
                targetKey = "conversations_list",
                tooltipPosition = com.forge.os.presentation.components.TooltipPosition.TOP
            )
        )

        com.forge.os.presentation.components.CoachMarkOverlay(
            steps = tutorialSteps,
            currentStep = tutorialStep,
            onNext = { tutorialStep++ },
            onSkip = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.CONVERSATIONS)
            },
            onDone = {
                showTutorial = false
                tutorialManager.markTutorialShown(com.forge.os.domain.tutorial.TutorialType.CONVERSATIONS)
            }
        )
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    renameTarget?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation", color = forgePalette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(c.id, title); renameTarget = null
                }) { Text("Save", color = forgePalette.orange) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = forgePalette.textMuted)
                }
            },
            containerColor = forgePalette.surface
        )
    }

    // ── Delete dialog ─────────────────────────────────────────────────────
    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${c.title}\"?", color = forgePalette.textPrimary) },
            text = { Text("This cannot be undone.", color = forgePalette.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(c.id); deleteTarget = null
                }) { Text("Delete", color = forgePalette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = forgePalette.textMuted)
                }
            },
            containerColor = forgePalette.surface
        )
    }
}

// ── Relative timestamp helper ─────────────────────────────────────────────
private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 2 -> "Yesterday"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}

// ── Conversation Card ─────────────────────────────────────────────────────
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
private fun ConversationCard(
    conv: StoredConversation,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit) {
    var showContextMenu by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isCurrent) forgePalette.orange else forgePalette.border,
        animationSpec = tween(200),
        label = "card_border"
    )

    val lastMessage = conv.messages.lastOrNull { it.role == "user" || it.role == "assistant" }
    val preview = lastMessage?.content?.take(120) ?: "No messages yet"
    val previewPrefix = when (lastMessage?.role) {
        "user" -> "You: "
        "assistant" -> ""
        else -> ""
    }

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(forgePalette.surface)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { showContextMenu = true }
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(forgePalette.orange, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    conv.title.ifBlank { "Untitled" },
                    color = forgePalette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    relativeTime(conv.updatedAt),
                    color = forgePalette.textMuted,
                    fontSize = 11.sp
                )
            }

            // Preview
            Text(
                "$previewPrefix$preview",
                color = forgePalette.textMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )

            // Meta row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${conv.messages.size} messages",
                    color = forgePalette.textMuted.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                conv.lastModel?.let {
                    Text(
                        it,
                        color = forgePalette.textMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Context menu on long press
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(forgePalette.surfaceElevated, RoundedCornerShape(12.dp))
        ) {
            DropdownMenuItem(
                text = { Text("Rename", color = forgePalette.textPrimary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null,
                        tint = forgePalette.orange, modifier = Modifier.size(18.dp))
                },
                onClick = { showContextMenu = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = forgePalette.danger, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null,
                        tint = forgePalette.danger, modifier = Modifier.size(18.dp))
                },
                onClick = { showContextMenu = false; onDelete() }
            )
        }
    }
}
