package com.forge.os.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.security.ApiKeyProvider

/**
 * First-launch onboarding wizard. Three pages:
 *   1. Welcome — explain Forge OS in one screen
 *   2. Capabilities — show what the agent can do, what permissions it asks for
 *   3. API key — pick provider + paste key (BYOK)
 *
 * On finish, persists hasOnboarded=true + saves the API key, then calls onDone().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var page by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Welcome to Forge OS") })
        },
        bottomBar = {
            BottomAppBar {
                Spacer(Modifier.weight(1f))
                if (page > 0) {
                    TextButton(onClick = { page-- }) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                }
                if (page < 2) {
                    Button(onClick = { page++ }) { Text("Next") }
                } else {
                    Button(
                        enabled = state.canFinish && !state.busy,
                        onClick = {
                            viewModel.finish {
                                onDone()
                            }
                        }
                    ) { Text(if (state.busy) "Saving…" else "Get Started") }
                }
                Spacer(Modifier.width(16.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Page indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { i ->
                    val color = if (i == page) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                    Surface(
                        modifier = Modifier.padding(4.dp).size(10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = color
                    ) {}
                }
            }

            when (page) {
                0 -> WelcomePage()
                1 -> CapabilitiesPage()
                2 -> ApiKeyPage(
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

@Composable
private fun WelcomePage() {
    Text(
        text = "🛠 Forge OS",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "An on-device AI agent operating system. Forge runs locally, " +
        "uses your API key, and gives an LLM agent a sandboxed workspace " +
        "to read/write files, run scripts, schedule tasks, install plugins, " +
        "and delegate work to sub-agents — all on your phone.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Privacy first", fontWeight = FontWeight.SemiBold)
            Text(
                "• Your API key is stored encrypted on this device only.\n" +
                "• Conversations & files never leave your device except to your chosen LLM provider.\n" +
                "• Tools run in a sandbox under Forge's app data directory.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CapabilitiesPage() {
    Text("What Forge can do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val items = listOf(
        "🧠 Memory"        to "Three-tier memory (working, daily, long-term) the agent recalls across sessions, with semantic embeddings — the agent is taught to write to it after big results and search it before research.",
        "📁 Workspace"     to "Sandboxed file system the agent can read, write, search, and share — full file manager UI with device upload + browser-side file picking.",
        "🐍 Code"          to "Run Python (Chaquopy) and shell commands with timeouts, captured output, and saved-as-skill replay.",
        "⏰ Cron + Alarms" to "Schedule recurring jobs and one-shot alarms (RUN / NOTIFY / PROMPT_AGENT) in plain English; the Alarms screen shows a session log of every fire.",
        "🌐 Browser"       to "Headless and on-screen browsers with viewport / user-agent control (desktop, laptop, tablet, mobile), region screenshots, link extraction, and selector-aware tools.",
        "🧩 Plugins"       to "Install Python plugins (.fp / .zip) that extend the agent's tool surface at runtime — and the agent itself can scaffold a brand-new plugin via the plugin_create tool.",
        "🛰 MCP Client"    to "Connect to Model Context Protocol servers to import external tools and resources.",
        "🤖 Sub-agents"    to "Spawn focused sub-agents in parallel, delegate work, and aggregate their results.",
        "💛 Companion"     to "A separate warmer mode for everyday conversation, with persona, episodic memory, and crisis-aware safety.",
        "📸 Snapshots"     to "Time-travel your workspace: snapshot, browse, diff, and restore previous states.",
        "🔌 External API"  to "Other Android apps can call Forge as an on-device LLM service via a permission-gated AIDL interface.",
        "💰 Cost meter"    to "Live token & USD spend per call / session / lifetime, with optional Compact Mode to keep costs down.",
        "🧰 Multi-provider" to "Bring your own key for OpenAI, Anthropic, Groq, Gemini, OpenRouter, xAI, DeepSeek, Mistral, Together, Cerebras, or a local Ollama — switch on the fly.",
        "🔁 Fallback chain" to "Configure a global model fallback chain so cron, alarms, and background work keep going even when the primary provider rate-limits or errors out.",
        "🔐 Permissions"   to "Per-tool permissions and per-provider keys you control from Settings; keys are encrypted on-device."
    )
    items.forEach { (k, v) ->
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(k, fontWeight = FontWeight.SemiBold)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyPage(
    provider: ApiKeyProvider,
    apiKey: String,
    error: String?,
    onProviderChange: (ApiKeyProvider) -> Unit,
    onKeyChange: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Text("Connect your LLM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Forge uses your API key directly — bring your own key from any supported provider. " +
        "You can change this later in Settings.",
        style = MaterialTheme.typography.bodyMedium
    )

    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = !menuOpen }) {
        OutlinedTextField(
            value = provider.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            ApiKeyProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName) },
                    onClick = { onProviderChange(p); menuOpen = false }
                )
            }
        }
    }

    OutlinedTextField(
        value = apiKey,
        onValueChange = onKeyChange,
        label = { Text("API key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showKey = !showKey }) {
                Text(if (showKey) "Hide" else "Show")
            }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        modifier = Modifier.fillMaxWidth()
    )

    Text(
        "Don't have a key? You can also point Forge at a local Ollama server in Settings — " +
        "no key required.",
        style = MaterialTheme.typography.bodySmall
    )
}
