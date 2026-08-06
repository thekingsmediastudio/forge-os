package com.forge.os.presentation.screens.companion

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.screens.common.ForgeOsPalette

/**
 * Phase H/I/P — Companion home. Visual identity intentionally softer than
 * the AGENT chat: warmer accent, rounded bubbles, persona name in the header.
 */
private val CompanionAccent = Color(0xFFf59e0b)   // warm amber
private val UserBubbleBg    = Color(0xFF1f1b16)
private val FriendBubbleBg  = Color(0xFF18120a)
private val FriendBorder    = Color(0xFF3a2a13)

@Composable
fun CompanionScreen(
    onBack: () -> Unit,
    onOpenPersona: () -> Unit,
    onSwitchToAgent: () -> Unit = onBack,
    onOpenHistory: () -> Unit = {},
    vm: CompanionViewModel = hiltViewModel()) {
    val persona by vm.personaManager.persona.collectAsState()
    val messages by vm.messages.collectAsState()
    val phase by vm.phase.collectAsState()
    val relationship by vm.relationshipState.snapshot.collectAsState()
    val pendingImage by vm.pendingImage.collectAsState()
    val isBusy = phase != CompanionPhase.IDLE
    var input by remember { mutableStateOf("") }
    var showPersonaSwitch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Image picker — stages an image (base64 for vision + saved to workspace)
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val resolver = ctx.contentResolver
                var fileName = "photo.jpg"
                var fileSize = 0L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            .takeIf { it >= 0 }?.let { fileName = c.getString(it) }
                        c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            .takeIf { it >= 0 }?.let { fileSize = c.getLong(it) }
                    }
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                // Persist into workspace/uploads so the path survives restarts
                val uploadsDir = java.io.File(ctx.filesDir, "workspace/uploads").apply { mkdirs() }
                val dest = java.io.File(uploadsDir, "${System.currentTimeMillis()}-$fileName")
                dest.writeBytes(bytes)
                vm.stageImage(
                    com.forge.os.domain.agent.FileAttachment(
                        filePath = dest.absolutePath,
                        fileName = fileName,
                        mimeType = mimeType,
                        fileSize = fileSize,
                        base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            }
        }
    }

    LaunchedEffect(Unit) { vm.greet() }
    // Phase L — if a notification deep-linked us here with a seed prompt,
    // pre-fill the input so the user can edit before sending.
    LaunchedEffect(Unit) {
        PendingCompanionSeed.consume()?.let { input = it }
    }
    // Phase J1: ensure the session is summarised when the user leaves the screen,
    // not just when the ViewModel is finally cleared by the platform.
    DisposableEffect(Unit) { onDispose { vm.endSession() } }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(ForgeOsPalette.Bg)) {
        // Header — distinct from ModuleScaffold to give companion its own identity
        Row(
            Modifier.fillMaxWidth().background(ForgeOsPalette.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = ForgeOsPalette.TextMuted, fontSize = 18.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp))
            Text("💛", fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(persona.name, color = CompanionAccent, fontSize = 16.sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.clickable { showPersonaSwitch = true })
                // Phase N-3 — quiet relationship counter (no streaks/levels).
                val sub = if (relationship.totalConversations > 0)
                    "Day ${relationship.daysKnown()} · we've talked ${relationship.totalConversations} time${if (relationship.totalConversations == 1) "" else "s"}"
                else
                    "companion mode"
                Text(sub, color = ForgeOsPalette.TextMuted,
                    fontSize = 10.sp, letterSpacing = 1.sp)
            }
            // Phase P-5 — switch back to AGENT at the top of the screen.
            Text("⚡", fontSize = 16.sp,
                modifier = Modifier
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                    .clickable { onSwitchToAgent() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(6.dp))
            Text("chats", color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                modifier = Modifier
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                    .clickable { onOpenHistory() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(6.dp))
            Text("persona", color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                modifier = Modifier
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                    .clickable { onOpenPersona() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { m ->
                val lastAssistantId = messages.lastOrNull { it.role == "assistant" }?.id
                Bubble(
                    m,
                    onRegenerate = if (m.id == lastAssistantId) ({ vm.regenerateLast() }) else ({}))
            }
            if (isBusy) item {
                val label = when (phase) {
                    CompanionPhase.LISTENING  -> "${persona.name} is listening…"
                    CompanionPhase.RESPONDING -> "${persona.name} is replying…"
                    CompanionPhase.IDLE       -> ""
                }
                TypingIndicator(label)
            }
        }

        // Phase P-3/P-6 — mood entry point (off if user disables in settings).
        val moodChipsEnabled = vm.moodChipsEnabled.collectAsState().value
        val checkInDoneToday by vm.checkInDoneToday.collectAsState()
        if (moodChipsEnabled && messages.size <= 1 && !checkInDoneToday) {
            MoodCheckInCard(
                personaName = persona.name,
                isBusy = isBusy,
                onSubmit = { mood, note -> vm.submitMoodCheckIn(mood, note) })
        } else if (moodChipsEnabled && messages.size <= 1) {
            Row(
                Modifier.fillMaxWidth().background(ForgeOsPalette.Surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("rough day", "ok", "good", "great").forEach { mood ->
                    Box(
                        Modifier
                            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(14.dp))
                            .clickable { input = "Today feels $mood. " }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(mood, color = CompanionAccent, fontSize = 11.sp)
                    }
                }
            }
        }

        // Staged image preview above the input
        pendingImage?.let { att ->
            Row(
                Modifier.fillMaxWidth().background(ForgeOsPalette.Surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                coil.compose.AsyncImage(
                    model = java.io.File(att.filePath),
                    contentDescription = "Attached image",
                    modifier = Modifier
                        .height(56.dp)
                        .width(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Spacer(Modifier.width(8.dp))
                Text(att.fileName, color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text("✕", color = ForgeOsPalette.TextMuted, fontSize = 14.sp,
                    modifier = Modifier.clickable { vm.clearStagedImage() }.padding(8.dp))
            }
        }

        // Input
        Row(
            Modifier.fillMaxWidth().background(ForgeOsPalette.Surface).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Attach image
            Text("📷", fontSize = 16.sp,
                modifier = Modifier
                    .clickable(enabled = !isBusy) { imagePicker.launch("image/*") }
                    .padding(end = 8.dp))
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(ForgeOsPalette.Surface2, RoundedCornerShape(22.dp))
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = ForgeOsPalette.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(CompanionAccent),
                    modifier = Modifier.fillMaxWidth())
                if (input.isEmpty()) {
                    Text("Tell ${persona.name} what's on your mind…",
                        color = ForgeOsPalette.TextDim, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Voice input — recognized speech lands in the text field for editing
            com.forge.os.presentation.screens.voice.VoiceInputButton(
                onVoiceInput = { spoken ->
                    input = if (input.isBlank()) spoken else "$input $spoken"
                },
                modifier = Modifier.height(44.dp).width(44.dp))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(CompanionAccent, RoundedCornerShape(22.dp))
                    .clickable(enabled = (input.isNotBlank() || pendingImage != null) && !isBusy) {
                        vm.send(input); input = ""
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("send", color = Color.Black, fontSize = 13.sp)
            }
        }
    }

    // Persona quick-switch: voice presets without leaving the chat
    if (showPersonaSwitch) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPersonaSwitch = false },
            title = { Text("Quick persona switch", color = ForgeOsPalette.TextPrimary) },
            text = {
                Column {
                    Text(
                        "Change how ${persona.name} speaks. Fine-tune everything else on the persona screen.",
                        color = ForgeOsPalette.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    com.forge.os.domain.companion.PersonaVoice.entries.forEach { voice ->
                        val selected = persona.voice == voice
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) ForgeOsPalette.Surface2 else Color.Transparent)
                                .clickable {
                                    vm.personaManager.update { it.copy(voice = voice) }
                                    showPersonaSwitch = false
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (voice) {
                                    com.forge.os.domain.companion.PersonaVoice.CASUAL -> "😊 Casual"
                                    com.forge.os.domain.companion.PersonaVoice.FORMAL -> "🎩 Formal"
                                    com.forge.os.domain.companion.PersonaVoice.PLAYFUL -> "🎉 Playful"
                                },
                                color = if (selected) CompanionAccent else ForgeOsPalette.TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            if (selected) Text("✓", color = CompanionAccent, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Text("edit full persona", color = ForgeOsPalette.TextMuted, fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        showPersonaSwitch = false
                        onOpenPersona()
                    }.padding(8.dp))
            },
            containerColor = ForgeOsPalette.Surface)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: CompanionMessage, onRegenerate: () -> Unit = {}) {
    val isUser = m.role == "user"
    val crisis = m.isCrisisResponse
    var showActions by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val bg = when {
        crisis -> Color(0xFF1f0a0a)
        isUser -> UserBubbleBg
        else   -> FriendBubbleBg
    }
    val border = when {
        crisis -> ForgeOsPalette.Danger
        isUser -> ForgeOsPalette.Border
        else   -> FriendBorder
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .background(bg, RoundedCornerShape(16.dp))
                .border(1.dp, border, RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = { if (!m.isStreaming) showActions = true })
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (!isUser) {
                Text(
                    if (crisis) "support" else "companion",
                    color = if (crisis) ForgeOsPalette.Danger else CompanionAccent,
                    fontSize = 9.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
            }
            // Attached image thumbnail (user messages)
            m.imagePath?.let { path ->
                coil.compose.AsyncImage(
                    model = java.io.File(path),
                    contentDescription = "Attached image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Spacer(Modifier.height(6.dp))
            }
            if (isUser) {
                Text(
                    m.content,
                    color = if (m.isError) ForgeOsPalette.Danger else ForgeOsPalette.TextPrimary,
                    fontSize = 14.sp)
            } else {
                // Rich rendering for companion replies: markdown, code blocks,
                // links, lists, tables. User messages stay plain text.
                com.forge.os.presentation.screens.MarkdownText(
                    text = m.content,
                    baseColor = if (m.isError) ForgeOsPalette.Danger else ForgeOsPalette.TextPrimary,
                    baseFontSize = 14f)
            }
            // Phase K-4: tiny tag chip on user bubbles after classification.
            m.tags?.let { t ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${t.intent.name.lowercase()} · ${t.emotion}" +
                        (if (t.urgency > 0) " · u${t.urgency}" else ""),
                    color = ForgeOsPalette.TextDim,
                    fontSize = 9.sp)
            }

            // Long-press actions: copy / share / regenerate
            androidx.compose.material3.DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.content))
                        showActions = false
                    })
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, m.content)
                        }
                        runCatching {
                            ctx.startActivity(android.content.Intent.createChooser(intent, null)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        showActions = false
                    })
                if (!isUser && !crisis) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Regenerate") },
                        onClick = {
                            showActions = false
                            onRegenerate()
                        })
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(label, color = ForgeOsPalette.TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Start)
    }
}

/**
 * Phase P-6 — daily mood check-in card. Shown once per day before the first
 * message; logging a mood shares it with the companion as a chat turn.
 */
@Composable
private fun MoodCheckInCard(
    personaName: String,
    isBusy: Boolean,
    onSubmit: (mood: Int, note: String) -> Unit,
) {
    var selectedMood by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var noteOpen by remember { mutableStateOf(false) }
    val moods = listOf(
        1 to "😞", 2 to "😕", 3 to "🙂", 4 to "😊", 5 to "😄")

    Column(
        Modifier.fillMaxWidth().background(ForgeOsPalette.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("quick check-in", color = ForgeOsPalette.TextMuted,
            fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            moods.forEach { (value, emoji) ->
                val selected = selectedMood == value
                Box(
                    Modifier
                        .border(
                            1.dp,
                            if (selected) CompanionAccent else ForgeOsPalette.Border,
                            RoundedCornerShape(12.dp))
                        .background(
                            if (selected) ForgeOsPalette.Surface2 else Color.Transparent,
                            RoundedCornerShape(12.dp))
                        .clickable(enabled = !isBusy) { selectedMood = value }
                        .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(emoji, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (noteOpen) "hide note" else "+ note",
                color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                modifier = Modifier
                    .clickable { noteOpen = !noteOpen }
                    .padding(4.dp))
        }
        if (noteOpen) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(40.dp)
                    .background(ForgeOsPalette.Surface2, RoundedCornerShape(8.dp))
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = note, onValueChange = { note = it },
                    textStyle = TextStyle(color = ForgeOsPalette.TextPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(CompanionAccent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (note.isEmpty()) {
                    Text("anything on your mind? (optional)",
                        color = ForgeOsPalette.TextDim, fontSize = 12.sp)
                }
            }
        }
        if (selectedMood > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .background(
                            if (isBusy) ForgeOsPalette.Surface2 else CompanionAccent,
                            RoundedCornerShape(14.dp))
                        .clickable(enabled = !isBusy) { onSubmit(selectedMood, note) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("log & tell $personaName", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
    }
}
