# Forge OS

**Forge OS** is an on-device, agentic Android operating-system layer for Large
Language Models. You bring your own provider key (OpenAI, Anthropic, Groq,
Gemini, OpenRouter, xAI, DeepSeek, Mistral, Together, Cerebras, or a local
Ollama) and Forge gives the model a persistent memory, a sandboxed file
workspace, a real Python runtime, a headless browser, schedulable jobs,
plugins, sub-agents, snapshots, and a permission-gated AIDL service that other
Android apps can call. Everything runs on the phone — there is no server.

> Built around a ReAct-style agent loop on top of Jetpack Compose, Hilt, and
> Chaquopy. Designed to keep going on flaky networks, dead provider keys, or
> while the screen is off.

---

## Highlights

| Pillar | What it gives you |
| --- | --- |
| 🧠 **Three-tier memory** | Working / daily / long-term tiers with semantic embeddings. The agent is taught to *write* to memory after meaningful results and *search* it before research. |
| 📁 **Sandboxed workspace** | Full file manager UI, device→workspace upload, browser→workspace file picking, snapshots & diffs. Quotas enforced at the policy layer. |
| 🐍 **Code execution** | Python (Chaquopy 3.11) and shell with timeouts, captured output, AST-based import filtering, and "save as skill" replay. |
| ⏰ **Cron + alarms** | Plain-English schedules ("every 30 m", "daily at 9 am"), exact-time alarms with `RUN` / `NOTIFY` / `PROMPT_AGENT` actions, and a per-alarm session log of every fire. |
| 🌐 **Browsers** | Headless agent browser **and** an on-screen Browser tab that share cookies. Configurable viewport / User-Agent presets (desktop / laptop / tablet / mobile), region screenshots, link extraction, selector-aware text/attribute readers. |
| 🧩 **Plugins** | Install Python plugins (`.fp` / `.zip`) that extend the agent's tool surface. The agent itself can scaffold a brand-new plugin via `plugin_create`. |
| 🛰 **MCP client** | Connect to Model Context Protocol servers and import their tools and resources. |
| 🤖 **Sub-agents** | Spawn focused sub-agents in parallel, delegate work, and aggregate results. |
| 💛 **Companion** | A separate, warmer mode for everyday conversation, with persona, episodic memory, and crisis-aware safety. |
| 📸 **Snapshots** | Time-travel your workspace: snapshot, browse, diff, and restore. |
| 🔌 **External API** | Other Android apps can call Forge as an on-device LLM service via a permission-gated AIDL interface. |
| 💰 **Cost meter** | Live token / USD spend per call, per session, and lifetime, with optional Compact Mode to shrink prompts. |
| 🔁 **Global fallback chain** | Cron, alarms, sub-agents, and proactive turns automatically walk a configurable provider chain when the primary key rate-limits or errors. |
| 🔐 **Permissions** | Per-tool permissions and per-provider keys, all controlled from Settings. Keys are encrypted on-device. |
| 🌳 **Git in your pocket** | First-class `git_*` tools (init / add / commit / branch / log / diff / push / pull / clone) backed by JGit. PATs come from the encrypted memory store; HTTPS only. |
| ⬇️ **Streaming downloads** | `file_download` for the open web and `browser_download` for cookie-gated pages — both stream straight into `downloads/`, sniff the MIME, and return SHA-256 + byte count. |
| 🔒 **Padlock the agent can't open** | Settings → *Advanced overrides* lets the human add extra blocked hosts, blocked file extensions, and blocked config keys; flipping *lockAgentOut* freezes every `permissions.*` key against the agent. |
| 🧭 **Editable model routing** | Settings → *Model routing* exposes the global fallback chain as drag-free up/down arrows, plus a switch for whether background callers (companion, alarms, cron) are allowed to fall back. |

---

## Architecture (one paragraph)

The agent loop ([`ReActAgent`](app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt))
emits an `AgentEvent` flow consumed by the UI. Tool calls are routed through
[`ToolRegistry`](app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt),
which fans out to managers for memory, sandbox, browser, cron, alarms,
plugins, MCP, sub-agents, and Android device control. Provider calls go
through [`AiApiManager`](app/src/main/java/com/forge/os/data/api/AiApiManager.kt)
which exposes both `chat()` and `chatWithFallback()` — the latter walks
`ModelRoutingConfig.fallbackChain` on any `ApiCallException`, so a dead
primary doesn't kill the turn. Hilt wires everything; Compose paints the
five tabs (Chat, Workspace, Browser, Hub, Settings) plus the Companion mode.

```
┌──────────────┐   AgentEvent flow   ┌──────────────┐
│  Compose UI  │◄───────────────────│  ReActAgent  │
└──────┬───────┘                    └──────┬───────┘
       │  user prompt                      │ chatWithFallback()
       ▼                                   ▼
┌──────────────┐  tool calls   ┌────────────────────┐
│ ToolRegistry │◄──────────────│   AiApiManager     │
└──────┬───────┘                └────────────────────┘
       │ fans out
       ├── SandboxManager · MemoryManager · CronManager · AlarmManager
       ├── HeadlessBrowser · BrowserSessionManager
       ├── PluginManager · DelegationManager · McpClient
       └── AndroidController · SnapshotManager · ChannelManager
```

---

## Getting started

1. **Build**: open the project in Android Studio (Giraffe+) and run on a
   device or emulator with API 28+.
2. **Onboarding**: pick a provider, paste your API key, accept permissions,
   and you're done. Keys are stored in EncryptedSharedPreferences.
3. **Optional integrations**: add Composio, Tavily, Brave Search, RAGFlow, or
   custom OpenAI-compatible endpoints from Settings.
4. **Optional MCP**: paste an MCP server URL in Settings → Tools → MCP.

> The app needs `SCHEDULE_EXACT_ALARM` for the Alarms screen and
> `POST_NOTIFICATIONS` for the agent's notify channel. Background work is
> handled by AlarmManager (alarms) and WorkManager (cron) and survives
> reboot.

---

## Notable feature deep-dives

### Alarms

- Set with `alarm_set { label, in_seconds | at_millis, action, payload, repeat_ms? }`
  where `action` ∈ `NOTIFY` / `RUN_TOOL` / `RUN_PYTHON` / `PROMPT_AGENT`.
- Every fire is recorded to `workspace/alarms/sessions.json` (200-event ring
  buffer) and visible under **Alarms → Sessions**.
- `PROMPT_AGENT` runs the prompt through the same loop as the chat, including
  tools and the global fallback chain.

### Headless browser

- `browser_set_viewport { device | width, height, user_agent }` — preset or
  custom (desktop, laptop, tablet, mobile).
- `browser_screenshot_region { selector | x,y,width,height, save_as }` — PNG
  to the workspace.
- `browser_wait_for_selector`, `browser_get_text`, `browser_get_attribute`,
  `browser_list_links` for selector-aware crawling.
- `file_upload_to_browser { selector, workspace_path }` opens the on-screen
  Browser tab's chooser, which can pick **from the workspace** or from the
  device.

### Workspace

- The Workspace screen now has an **Upload from device** action that streams a
  picked file (SAF) into the current folder, respecting per-file and total
  workspace quotas.
- Cookies, history, and snapshots all live inside the sandbox under
  `workspace/.{cookies,history,snapshots}`.

### Plugins

- Install via `.fp` / `.zip` from the Plugins screen, or have the agent call
  `plugin_create { id, name, description, tool_name, tool_description,
  python_code }` mid-loop. The new tool is callable on the next iteration.
- Plugins survive app upgrades via the Phase Q plugin exporter.

### Memory guidance

Rules #9 and #10 of the agent's system prompt explicitly require:

- **Aggressive writes**: after any meaningful result (research, scraping,
  long calculations, user preferences), call `memory_remember` so the next
  turn can reuse it.
- **Aggressive reads**: before doing research, call `memory_search` to avoid
  recomputing things you already know.

### Global fallback chain

- Configured in `ModelRoutingConfig.fallbackChain` (List<ProviderModelPair>).
- `AiApiManager.chatWithFallback()` is the single entry point for both
  foreground and background callers (cron, alarms, sub-agents, proactive).
- A failed primary triggers a sequential walk through the chain; each link is
  skipped if the user hasn't saved a key for it.

---

## Project layout

```
app/src/main/java/com/forge/os/
├── data/
│   ├── api/           AiApiManager, providers, embeddings, call log
│   ├── browser/       BrowserSessionManager, history, command bus
│   ├── companion/     Companion mode plumbing
│   ├── memory/        Three-tier store + embeddings
│   ├── sandbox/       SandboxManager, security policy, shell, Python
│   └── web/           HeadlessBrowser
├── domain/
│   ├── agent/         ReActAgent, ToolRegistry
│   ├── alarms/        AlarmManager, AlarmReceiver, AlarmSessionLog
│   ├── config/        ForgeConfig + ConfigRepository
│   ├── cron/          CronManager + WorkManager wiring
│   ├── delegation/    Sub-agent orchestrator
│   ├── mcp/           MCP client
│   └── plugins/       PluginManager, validator, exporter, BuiltInPlugins
└── presentation/
    ├── screens/       Compose screens (Chat, Workspace, Browser, Hub, …)
    └── theme/         Forge palette + typography
```

---

## License

MIT, with the usual disclaimer that bringing your own LLM key means you're
responsible for what the model does with the tools you give it. The
permission system, sandbox quotas, and tool padlocks exist for that reason —
keep them on for anything you don't fully trust.
