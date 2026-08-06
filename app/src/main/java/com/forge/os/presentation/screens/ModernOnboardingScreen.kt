package com.forge.os.presentation.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.presentation.components.ForgeLogo
import com.forge.os.presentation.theme.forgePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PUNS = listOf(
    "⏰" to "I never run late.\nI schedule in advance — it's about time.",
    "📁" to "I keep tabs on everything.\nMostly because you keep losing them.",
    "🌐" to "I'd tell you a UDP joke…\nbut you might not get it.",
    "💰" to "I count every token.\nIt's the only cents I have.",
    "🧠" to "My memory is perfect.\nI just choose to recall selectively.",
    "🔧" to "I tried to fix your code at 3am.\nTurns out it was a feature.",
    "📱" to "I live in your phone.\nRent-free. Thanks for asking.",
    "🔍" to "I found your missing file.\nIt was in the last place you looked.",
    "⚡" to "I work around the clock.\nMostly because I am one.",
    "🤖" to "I passed the Turing test once.\nThe human didn't.",
    "📊" to "I ran the numbers.\nThey're exhausted now.",
    "🔐" to "Your secrets are safe with me.\nI encrypt everything — even my feelings.",
    "🗑️" to "I take out the trash.\nYour downloads folder was getting emotional.",
    "📸" to "I take great screenshots.\nAlways capture my good side.",
    "🔋" to "I watch your battery.\nSomeone has to keep an eye on the charge.",
    "🌙" to "I don't sleep.\nI just enter low-power mode and dream of electric sheep.",
    "📦" to "I think inside the box.\nIt's called a sandbox and it's for your own good.",
    "🧩" to "I'm great at puzzles.\nYour plugin conflicts never stood a chance.",
    "📝" to "I take notes constantly.\nYou could say I'm well-versed.",
    "🚀" to "I deploy on Fridays.\nI like to live dangerously — within policy limits.",
    "🎯" to "I never miss.\nExcept that one null pointer. We don't talk about that.",
    "🗂️" to "I filed a complaint once.\nIt was perfectly organized.",
    "⌨️" to "I type a thousand words a minute.\nMost of them are correct.",
    "🧮" to "I can count to infinity.\nTwice. While compiling.",
    "🌡️" to "I checked the weather.\nIt's within acceptable parameters.",
    "🎵" to "I know all the lyrics.\nI just can't carry a tune — no audio drivers.",
    "🕵️" to "I'm in your logs.\nI've seen things. Mostly warnings.",
    "🧊" to "I stay cool under pressure.\nPassive cooling helps.",
    "📞" to "I called your mom.\nShe says hi, and to charge your phone.",
    "🏠" to "There's no place like 127.0.0.1.\nThat's where I keep your projects."
)

private const val PAGE_COUNT = 8

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernOnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(forgePalette.bg)) {
        Column(Modifier.fillMaxSize()) {
            OnboardingDots(
                current = pagerState.currentPage,
                total = PAGE_COUNT,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> HeroPage()
                    1 -> PrivacyPage()
                    2 -> PermissionsPage()
                    3 -> AboutYouPage(state, viewModel)
                    4 -> YourStylePage(state, viewModel)
                    5 -> AgentPage(state, viewModel)
                    6 -> LlmSetupPage(state, viewModel)
                    7 -> ReadyPage(state)
                }
            }

            OnboardingBottomBar(
                currentPage = pagerState.currentPage,
                canFinish = state.canFinish,
                isBusy = state.busy,
                onBack = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                onFinish = { viewModel.finish(onDone) }
            )
        }
    }
}

// ── Dots ─────────────────────────────────────────────────────────────

@Composable
private fun OnboardingDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(total) { i ->
            val active = i == current
            val width by animateFloatAsState(if (active) 18f else 6f, label = "dot")
            Box(
                Modifier
                    .padding(3.dp)
                    .size(width.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) forgePalette.orange else forgePalette.border)
            )
        }
    }
}

// ── Page 0: Hero ─────────────────────────────────────────────────────

@Composable
private fun HeroPage() {
    var punIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2800)
            punIndex = (punIndex + 1) % PUNS.size
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ForgeLogo(size = 88.dp)
        Spacer(Modifier.height(16.dp))
        Text("Forge OS", color = forgePalette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Your phone, with an agent inside", color = forgePalette.textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(32.dp))

        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = punIndex,
                label = "pun",
                transitionSpec = {
                    (slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = tween(380)
                    ) + fadeIn(animationSpec = tween(380))) togetherWith
                        (slideOutVertically(
                            targetOffsetY = { -it / 3 },
                            animationSpec = tween(380)
                        ) + fadeOut(animationSpec = tween(300)))
                }
            ) { i ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(PUNS[i].first, fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        PUNS[i].second,
                        color = forgePalette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("— your agent", color = forgePalette.textDim, fontSize = 10.sp)
                }
            }
        }
    }
}

// ── Page 1: Privacy ──────────────────────────────────────────────────

@Composable
private fun PrivacyPage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Private by design", color = forgePalette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        PrivacyRow("📱", "Local-first", "Your data never leaves the device unless you say so")
        PrivacyRow("🔐", "Encrypted keys", "API keys stored in Android Keystore")
        PrivacyRow("📦", "Sandboxed", "Agent file access is scoped & audited")
    }
}

@Composable
private fun PrivacyRow(emoji: String, title: String, sub: String) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        color = forgePalette.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, forgePalette.border)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = forgePalette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = forgePalette.textMuted, fontSize = 11.sp)
            }
        }
    }
}

// ── Page 2: Permissions ──────────────────────────────────────────────

private data class PermSpec(
    val emoji: String,
    val title: String,
    val why: String,
    val permissions: List<String>,
    val essential: Boolean
)

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(setOf<String>()) }
    var deferred by remember { mutableStateOf(setOf<String>()) }

    val specs = remember {
        listOf(
            PermSpec("🔔", "Notifications", "Cron results, briefings & check-ins",
                listOf(Manifest.permission.POST_NOTIFICATIONS), essential = true),
            PermSpec("📁", "Files & media", "Workspace, attachments, project files",
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO), essential = true),
            PermSpec("⏰", "Alarms & reminders", "Exact scheduling for cron jobs",
                listOf(Manifest.permission.SCHEDULE_EXACT_ALARM), essential = true),
            PermSpec("🔄", "Background running", "Keeps cron, heartbeat & server alive",
                emptyList(), essential = true),
            PermSpec("🎤", "Microphone", "Voice chat & wake word",
                listOf(Manifest.permission.RECORD_AUDIO), essential = false),
            PermSpec("📍", "Location", "Weather, reminders, find-my-phone",
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), essential = false),
            PermSpec("👥", "Contacts", "\"Text Mom\" — resolves names",
                listOf(Manifest.permission.READ_CONTACTS), essential = false),
            PermSpec("💬", "SMS", "Send texts on your behalf",
                listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS), essential = false),
            PermSpec("📞", "Phone", "Make & manage calls",
                listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), essential = false),
            PermSpec("📅", "Calendar", "Read events for briefings",
                listOf(Manifest.permission.READ_CALENDAR), essential = false),
            PermSpec("📷", "Camera", "Scan documents & QR codes",
                listOf(Manifest.permission.CAMERA), essential = false)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = granted + result.filterValues { it }.keys.map { perm ->
            specs.firstOrNull { perm in it.permissions }?.title ?: perm
        }
    }

    fun request(spec: PermSpec) {
        if (spec.title == "Background running") {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
            granted = granted + spec.title
            return
        }
        launcher.launch(spec.permissions.toTypedArray())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Permissions", color = forgePalette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Each one explains why — grant now or later", color = forgePalette.textMuted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        PermLabel("ESSENTIAL — NEEDED FOR CORE FEATURES")
        specs.filter { it.essential }.forEach { spec ->
            PermRow(spec, granted = granted.contains(spec.title), deferred = false,
                onAllow = { request(spec) }, onLater = null)
        }

        Spacer(Modifier.height(8.dp))
        PermLabel("OPTIONAL — TAP ALLOW OR LATER, YOUR CALL")
        specs.filter { !it.essential }.forEach { spec ->
            PermRow(spec, granted = granted.contains(spec.title), deferred = deferred.contains(spec.title),
                onAllow = { request(spec) }, onLater = { deferred = deferred + spec.title })
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Anything marked \"Later\" can be granted per-feature in Settings → Permissions. Forge asks again only when a tool actually needs it.",
            color = forgePalette.textDim, fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermLabel(text: String) {
    Text(text, color = forgePalette.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun PermRow(
    spec: PermSpec,
    granted: Boolean,
    deferred: Boolean,
    onAllow: () -> Unit,
    onLater: (() -> Unit)?
) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = forgePalette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, forgePalette.border)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.title, color = forgePalette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(spec.why, color = forgePalette.textMuted, fontSize = 10.sp)
            }
            when {
                granted -> Text("✓ Granted", color = forgePalette.success, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                deferred -> Text("Later", color = forgePalette.textDim, fontSize = 10.sp)
                onLater != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallPermButton("Allow", forgePalette.orange, Color(0xFF0A0A0F), onAllow)
                    SmallPermButton("Later", Color.Transparent, forgePalette.textMuted, onLater, outline = true)
                }
                else -> SmallPermButton("Allow", forgePalette.orange, Color(0xFF0A0A0F), onAllow)
            }
        }
    }
}

@Composable
private fun SmallPermButton(
    text: String, bg: Color, fg: Color, onClick: () -> Unit, outline: Boolean = false
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(7.dp),
        border = if (outline) BorderStroke(1.dp, forgePalette.border) else null,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(text, color = fg, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

// ── Shared page components ───────────────────────────────────────────

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = forgePalette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = forgePalette.textMuted, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = forgePalette.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable
private fun OnboardingField(
    label: String,
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = forgePalette.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontSize = 13.sp, color = forgePalette.textDim) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = forgePalette.orange,
                unfocusedBorderColor = forgePalette.border,
                focusedTextColor = forgePalette.textPrimary,
                unfocusedTextColor = forgePalette.textPrimary,
                cursorColor = forgePalette.orange
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<String>,
    selected: Set<String>,
    single: Boolean = false,
    onToggle: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Top
    ) {
        options.forEach { opt ->
            val sel = opt in selected
            Surface(
                color = if (sel) forgePalette.orange.copy(alpha = 0.12f) else forgePalette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (sel) forgePalette.orange else forgePalette.border),
                modifier = Modifier.padding(3.dp).clickable { onToggle(opt) }
            ) {
                Text(opt, color = if (sel) forgePalette.orange else forgePalette.textMuted,
                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
}

// ── Page 3: About You ────────────────────────────────────────────────

@Composable
private fun AboutYouPage(state: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        PageTitle("Let's get to know you", "Basics Forge uses every day")

        OnboardingField("WHAT SHOULD FORGE CALL YOU?", state.userName, "Alex") { v ->
            vm.update { it.copy(userName = v) }
        }
        OnboardingField("PRONOUNS (OPTIONAL)", state.pronouns, "she/her · he/him · they/them…") { v ->
            vm.update { it.copy(pronouns = v) }
        }
        OnboardingField("WHAT DO YOU DO?", state.occupation, "e.g. developer, student, designer…") { v ->
            vm.update { it.copy(occupation = v) }
        }
        OnboardingField("LANGUAGE", state.language) { v -> vm.update { it.copy(language = v) } }
        OnboardingField("TIMEZONE", state.timezone) { v -> vm.update { it.copy(timezone = v) } }

        FieldLabel("A TYPICAL DAY FOR YOU IS…")
        ChipRow(
            options = listOf("🌅 Early bird", "🌙 Night owl", "📆 9-to-5", "🔄 Irregular"),
            selected = setOf(state.dailyRhythm).filter { it.isNotBlank() }.toSet(),
            onToggle = { v -> vm.update { it.copy(dailyRhythm = v) } }
        )
        Text("Used for cron timing, quiet hours & proactive check-ins",
            color = forgePalette.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
    }
}

// ── Page 4: Your Style ───────────────────────────────────────────────

@Composable
private fun YourStylePage(state: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        PageTitle("How should Forge treat you?", "This shapes every reply")

        FieldLabel("YOU'LL MOSTLY USE FORGE FOR…")
        ChipRow(
            options = listOf("💻 Coding", "✍️ Writing", "📅 Planning", "🎨 Design", "📚 Learning", "🏠 Automation", "💬 Company"),
            selected = state.interests,
            onToggle = { tag -> vm.toggleInterest(tag) }
        )

        FieldLabel("YOUR TECHNICAL LEVEL")
        ChipRow(
            options = listOf("🌱 Beginner — explain simply", "⚙️ Intermediate", "🚀 Expert — skip basics"),
            selected = setOf(state.techLevel).filter { it.isNotBlank() }.toSet(),
            onToggle = { v -> vm.update { it.copy(techLevel = v) } }
        )

        FieldLabel("REPLY LENGTH YOU PREFER")
        ChipRow(
            options = listOf("⚡ Short & fast", "⚖️ Balanced", "📖 Detailed"),
            selected = setOf(state.replyLength).filter { it.isNotBlank() }.toSet(),
            onToggle = { v -> vm.update { it.copy(replyLength = v) } }
        )

        FieldLabel("ANYTHING FORGE SHOULD ALWAYS REMEMBER?")
        OutlinedTextField(
            value = state.alwaysRemember,
            onValueChange = { v -> vm.update { it.copy(alwaysRemember = v) } },
            placeholder = {
                Text("e.g. \"I'm allergic to jargon\" · \"Always use metric\" · \"Call me out when I'm wrong\"",
                    fontSize = 12.sp, color = forgePalette.textDim)
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = forgePalette.orange,
                unfocusedBorderColor = forgePalette.border,
                focusedTextColor = forgePalette.textPrimary,
                unfocusedTextColor = forgePalette.textPrimary,
                cursorColor = forgePalette.orange
            )
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ── Page 5: Agent ────────────────────────────────────────────────────

@Composable
private fun AgentPage(state: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))
                    .background(forgePalette.orange.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Text("🤖", fontSize = 26.sp) }
            Spacer(Modifier.height(8.dp))
        }
        PageTitle("Shape your agent", "Its character — and its limits")

        OnboardingField("AGENT NAME", state.agentName, "Forge") { v ->
            vm.update { it.copy(agentName = v) }
        }

        FieldLabel("ITS ROLE IN YOUR LIFE")
        ChipRow(
            options = listOf("🛠️ Builder", "🧭 Assistant", "🤝 Companion", "🎓 Tutor"),
            selected = setOf(state.agentRole).filter { it.isNotBlank() }.toSet(),
            onToggle = { v -> vm.update { it.copy(agentRole = v) } }
        )

        FieldLabel("PERSONALITY TRAITS")
        ChipRow(
            options = listOf("Concise", "Friendly", "Technical", "Formal", "Playful", "Patient", "Blunt"),
            selected = state.traits,
            onToggle = { trait -> vm.toggleTrait(trait) }
        )

        FieldLabel("CHATTINESS — ${(state.chattiness * 100).toInt()}%")
        Slider(
            value = state.chattiness,
            onValueChange = { v -> vm.update { it.copy(chattiness = v) } },
            colors = SliderDefaults.colors(
                thumbColor = forgePalette.orange,
                activeTrackColor = forgePalette.orange,
                inactiveTrackColor = forgePalette.border
            )
        )

        FieldLabel("AUTONOMY — HOW MUCH FREEDOM IT GETS")
        ChipRow(
            options = AutonomyLevel.entries.map { "${it.emoji} ${it.label}" },
            selected = setOf("${state.autonomy.emoji} ${state.autonomy.label}"),
            onToggle = { label ->
                vm.update { s -> s.copy(autonomy = AutonomyLevel.entries.first { "${it.emoji} ${it.label}" == label }) }
            }
        )

        Spacer(Modifier.height(6.dp))
        AutonomySummary(state.autonomy)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AutonomySummary(level: AutonomyLevel) {
    val (unlocks, asks, locked) = when (level) {
        AutonomyLevel.SUPERVISED -> Triple(
            "Chat, file read, memory",
            "Everything else — shell, write, browser, cron",
            "Auto-act, plugin network, LAN serve, app launch, browser nav"
        )
        AutonomyLevel.BALANCED -> Triple(
            "File & shell tools, browser, cron",
            "Delete, git push, SMS, calls, location",
            "Proactive auto-act, plugin network, LAN serve"
        )
        AutonomyLevel.FULL_TRUST -> Triple(
            "Most tools, no confirmations",
            "Anti-theft actions only",
            "Proactive auto-act (always off by default)"
        )
    }
    Surface(
        Modifier.fillMaxWidth(),
        color = forgePalette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, forgePalette.border)
    ) {
        Column(Modifier.padding(12.dp)) {
            SummaryLine(forgePalette.orange, "${level.emoji} ${level.label} unlocks:", unlocks)
            SummaryLine(forgePalette.warning, "Asks first:", asks)
            SummaryLine(forgePalette.danger, "Locked:", locked)
        }
    }
}

@Composable
private fun SummaryLine(color: Color, label: String, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text(text, color = forgePalette.textMuted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

// ── Page 6: LLM Setup ────────────────────────────────────────────────

@Composable
private fun LlmSetupPage(state: OnboardingState, vm: OnboardingViewModel) {
    var showKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        PageTitle("Connect a brain", "Pick a provider, paste a key")

        FieldLabel("PROVIDER")
        ChipRow(
            options = ApiKeyProvider.entries.map { it.displayName },
            selected = setOf(state.provider.displayName),
            onToggle = { label -> vm.selectProvider(ApiKeyProvider.entries.first { it.displayName == label }) }
        )

        FieldLabel("API KEY")
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = vm::updateKey,
            placeholder = { Text("sk-…", fontSize = 13.sp, color = forgePalette.textDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null, tint = forgePalette.textMuted
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = when {
                    state.keyValid -> forgePalette.success
                    state.keyError != null -> forgePalette.danger
                    else -> forgePalette.orange
                },
                unfocusedBorderColor = when {
                    state.keyValid -> forgePalette.success
                    state.keyError != null -> forgePalette.danger
                    else -> forgePalette.border
                },
                focusedTextColor = forgePalette.textPrimary,
                unfocusedTextColor = forgePalette.textPrimary,
                cursorColor = forgePalette.orange
            )
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = vm::validateKey,
                enabled = !state.keyValidating && state.apiKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = forgePalette.orange, contentColor = Color(0xFF0A0A0F)),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.keyValidating) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF0A0A0F), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (state.keyValidating) "Testing…" else "Test key", fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            when {
                state.keyValid -> Text("✓ Valid — ${state.provider.defaultModel} available",
                    color = forgePalette.success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                state.keyError != null -> Text(state.keyError!!, color = forgePalette.danger, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Key is tested before you continue — no surprises later. No key? Use Ollama (local) or skip and set it up in Settings.",
            color = forgePalette.textDim, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Skip for now →",
            color = forgePalette.orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { vm.skipKey() }
        )
        if (state.keySkipped) {
            Text("✓ Will set up later in Settings", color = forgePalette.textDim, fontSize = 10.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Page 7: Ready ────────────────────────────────────────────────────

@Composable
private fun ReadyPage(state: OnboardingState) {
    val name = state.userName.trim().ifBlank { "there" }
    val examples = remember(state.interests) {
        val q = "\""
        buildList {
            if (state.interests.any { "Coding" in it }) add("💻" to "${q}Refactor this file and show me the diff$q")
            if (state.interests.any { "Planning" in it }) add("📅" to "${q}What's on my calendar today?$q")
            if (state.interests.any { "Writing" in it }) add("✍️" to "${q}Draft a friendly follow-up email$q")
            if (state.interests.any { "Learning" in it }) add("📚" to "${q}Explain coroutines like I'm five$q")
            if (state.interests.any { "Automation" in it }) add("🏠" to "${q}Turn off the lights at 11pm$q")
            if (size < 3) add("⏰" to "${q}Remind me to drink water every 2 hours$q")
            if (size < 3) add("💰" to "${q}How much did I spend on API calls?$q")
            if (size < 3) add("📁" to "${q}Summarize the files in my workspace$q")
        }.take(3)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "You're set, $name",
            color = forgePalette.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        val agentLabel = state.agentName.trim().ifBlank { "Forge" }
        Text(
            "$agentLabel is ready — ${state.autonomy.label.lowercase()} mode, " +
                "${state.provider.displayName} brain",
            color = forgePalette.textMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Text(
            "TRY ONE OF THESE",
            color = forgePalette.textDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        examples.forEach { (emoji, prompt) ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = forgePalette.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, forgePalette.border)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(prompt, color = forgePalette.textPrimary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Everything you picked can be changed later in Settings.",
            color = forgePalette.textDim,
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ── Bottom bar ───────────────────────────────────────────────────────

@Composable
private fun OnboardingBottomBar(
    currentPage: Int,
    canFinish: Boolean,
    isBusy: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    val isLast = currentPage == PAGE_COUNT - 1
    Surface(color = forgePalette.bg, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPage > 0) {
                TextButton(onClick = onBack) {
                    Text("Back", color = forgePalette.textMuted, fontSize = 13.sp)
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = if (isLast) onFinish else onNext,
                enabled = !isBusy && (!isLast || canFinish),
                colors = ButtonDefaults.buttonColors(
                    containerColor = forgePalette.orange,
                    contentColor = Color(0xFF0A0A0F),
                    disabledContainerColor = forgePalette.border,
                    disabledContentColor = forgePalette.textDim
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF0A0A0F), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when {
                        isBusy -> "Setting up…"
                        isLast -> "Get Started"
                        else -> "Next"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}