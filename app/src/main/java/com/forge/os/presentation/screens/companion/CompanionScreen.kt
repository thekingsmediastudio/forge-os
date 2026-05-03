package com.forge.os.presentation.screens.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    vm: CompanionViewModel = hiltViewModel(),
) {
    val persona by vm.personaManager.persona.collectAsState()
    val messages by vm.messages.collectAsState()
    val phase by vm.phase.collectAsState()
    val relationship by vm.relationshipState.snapshot.collectAsState()
    val isBusy = phase != CompanionPhase.IDLE
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", color = ForgeOsPalette.TextMuted, fontSize = 18.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp))
            Text("💛", fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(persona.name, color = CompanionAccent, fontSize = 16.sp,
                    fontFamily = FontFamily.SansSerif)
                // Phase N-3 — quiet relationship counter (no streaks/levels).
                val sub = if (relationship.totalConversations > 0)
                    "Day ${relationship.daysKnown()} · we've talked ${relationship.totalConversations} time${if (relationship.totalConversations == 1) "" else "s"}"
                else
                    "companion mode"
                Text(sub, color = ForgeOsPalette.TextMuted,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            // Phase P-5 — switch back to AGENT at the top of the screen.
            Text("⚡", fontSize = 16.sp,
                modifier = Modifier
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                    .clickable { onSwitchToAgent() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(6.dp))
            Text("chats", color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(4.dp))
                    .clickable { onOpenHistory() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(6.dp))
            Text("persona", color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { m -> Bubble(m) }
            if (isBusy) item {
                val label = when (phase) {
                    CompanionPhase.LISTENING  -> "${persona.name} is listening…"
                    CompanionPhase.RESPONDING -> "${persona.name} is replying…"
                    CompanionPhase.IDLE       -> ""
                }
                TypingIndicator(label)
            }
        }

        // Phase P-3 — one-tap mood chips (off if user disables in settings).
        val moodChipsEnabled = vm.moodChipsEnabled.collectAsState().value
        if (moodChipsEnabled && messages.size <= 1) {
            Row(
                Modifier.fillMaxWidth().background(ForgeOsPalette.Surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("rough day", "ok", "good", "great").forEach { mood ->
                    Box(
                        Modifier
                            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(14.dp))
                            .clickable { input = "Today feels $mood. " }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(mood, color = CompanionAccent, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Input
        Row(
            Modifier.fillMaxWidth().background(ForgeOsPalette.Surface).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(ForgeOsPalette.Surface2, RoundedCornerShape(22.dp))
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = ForgeOsPalette.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(CompanionAccent),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (input.isEmpty()) {
                    Text("Tell ${persona.name} what's on your mind…",
                        color = ForgeOsPalette.TextDim, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(CompanionAccent, RoundedCornerShape(22.dp))
                    .clickable(enabled = input.isNotBlank() && !isBusy) {
                        vm.send(input); input = ""
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("send", color = Color.Black, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Bubble(m: CompanionMessage) {
    val isUser = m.role == "user"
    val crisis = m.isCrisisResponse
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
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .background(bg, RoundedCornerShape(16.dp))
                .border(1.dp, border, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (!isUser) {
                Text(
                    if (crisis) "support" else "companion",
                    color = if (crisis) ForgeOsPalette.Danger else CompanionAccent,
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                m.content,
                color = if (m.isError) ForgeOsPalette.Danger else ForgeOsPalette.TextPrimary,
                fontSize = 14.sp,
            )
            // Phase K-4: tiny tag chip on user bubbles after classification.
            m.tags?.let { t ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${t.intent.name.lowercase()} · ${t.emotion}" +
                        (if (t.urgency > 0) " · u${t.urgency}" else ""),
                    color = ForgeOsPalette.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator(label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(label, color = ForgeOsPalette.TextMuted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start)
    }
}
