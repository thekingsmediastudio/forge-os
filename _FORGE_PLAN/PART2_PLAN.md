# Forge OS — Part 2 Plan

> **Purpose:** Track every remaining piece of work needed to take Forge OS from "kernel works" to "daily-driver agent OS." Update the checkboxes as you go so you (and any contributor) always knows the current state.
>
> **Legend:**
> - `[x]` = shipped
> - `[~]` = partial / scaffold present
> - `[ ]` = not started
> - `[!]` = blocked or has bug

---

## 0. Current state snapshot (as of zip `forge-os-master_1776856880259`)

### Backend (mostly in place)
| Subsystem | Status | Notes |
|---|---|---|
| `data/api/AiApiManager.kt` | `[~]` | 5 BYOK providers wired, OpenAI + Anthropic schemas, no streaming, weak error reporting |
| `data/api/ApiModels.kt` | `[x]` | request/response DTOs |
| `data/sandbox/SandboxManager.kt` | `[x]` | `Result<T>` based, security policy gated |
| `data/sandbox/PythonRunner.kt` | `[x]` | Chaquopy bridge |
| `data/sandbox/ShellExecutor.kt` | `[x]` | shell exec inside sandbox |
| `data/sandbox/SecurityPolicy.kt` | `[x]` | path/permission rules |
| `data/workers/*` | `[x]` | Heartbeat / Cron / Memory compression workers |
| `domain/agent/ReActAgent.kt` | `[x]` | core loop |
| `domain/agent/ToolRegistry.kt` | `[x]` | Result-unwrapping fixed in earlier session |
| `domain/agents/DelegationManager.kt` | `[x]` | sub-agent spawning |
| `domain/cron/*` | `[x]` | jobs, scheduler, repository |
| `domain/memory/*` | `[x]` | Daily / Longterm / Skills / Index |
| `domain/plugins/*` | `[x]` | manager / repo / validator / builtins |
| `domain/security/SecureKeyStore.kt` | `[x]` | EncryptedSharedPreferences |
| `domain/security/PermissionManager.kt` | `[x]` | role gating (GUEST/USER/POWER/ADMIN) |
| `domain/heartbeat/*` | `[x]` | monitor + alerts |
| `domain/config/*` | `[x]` | repo + mutation engine |
| `domain/notifications/*` | `[x]` | NotificationHelper, AgentNotifier |
| `di/AppModule.kt` | `[!]` | needs Python provider safety guard from M0 fix |
| `ForgeApplication.kt` | `[!]` | needs `Python.start()` moved before `super.onCreate()` |

### Frontend (mostly missing)
| Screen | Status | Notes |
|---|---|---|
| `presentation/MainActivity.kt` | `[x]` | nav graph |
| `presentation/theme/{Color,Theme,Type}.kt` | `[~]` | exists but no light/dark switcher hookup |
| `presentation/screens/OnboardingScreen.kt` | `[x]` | initial setup |
| `presentation/screens/ChatScreen.kt` | `[~]` | works but no model picker, weak error display |
| `presentation/screens/SettingsScreen.kt` | `[~]` | basic; no theme picker, no per-provider config UI |
| `presentation/screens/StatusScreen.kt` | `[x]` | health view |
| `presentation/screens/WorkspaceScreen.kt` | `[~]` | scaffold only — needs file viewer/editor |
| **Tools UI** | `[x]` | Phase D — list, toggle, audit panel |
| **Plugin UI** | `[x]` | Phase D — install (paste/zip), toggle, uninstall |
| **Cron UI** | `[x]` | Phase D — list, create, run-now, history |
| **Memory UI** | `[x]` | Phase D — 3 tabs, search, JSON export/import |
| **Agents UI** | `[x]` | Phase D — list, spawn, transcript, cancel |
| **Projects UI** | `[x]` | Phase D — create, activate, scope edit |
| **Skills UI** | `[x]` | Phase D — edit, test-in-sandbox, JSON export/import |
| **Diagnostics / API log viewer** | `[ ]` | not built |

---

## M0 — Carry-over fixes (do before anything else) — ✅ DONE in zip `_1776856880259`

- [x] **F1.** Move `Python.start(AndroidPlatform(this))` ABOVE `super.onCreate()` in `ForgeApplication.kt`.
- [x] **F2.** Defensive guard in `di/AppModule.providePythonInstance()`.
- [ ] **F3.** Verify the app launches without the `GenericPlatform` crash on a real device. _(needs your confirmation on hardware)_

---

## Phase A — Unblock chat — ✅ SHIPPED in `exports/forge-os-phase-A/`

### A1. Model selection that works
- [x] **A1.1** `selectedSpec` + `autoRoute` in `ChatViewModel`.
- [x] **A1.2** `ModelPickerChip` composable in `ChatScreen` (dropdown of every keyed provider + custom endpoints + Auto-route).
- [x] **A1.3** `AiApiManager.chat(spec: ProviderSpec? = null)` bypasses auto-routing when a spec is passed.
- [x] **A1.4** Auto-route toggle is the first item in the picker dropdown.
- [ ] **A1.5** _(deferred — single-conversation app right now; revisit when M7 multi-conversation lands)_

### A2. Surface API failures properly
- [x] **A2.1** `data/api/ApiError.kt` — typed (`httpCode`, `providerCode`, `requestId`, `provider`, `model`, `raw`).
- [x] **A2.2** `ApiCallException` carries `ApiError`; ReActAgent forwards it via `AgentEvent.Error(message, error)`.
- [x] **A2.3** Inline red error bubble in `ChatScreen` shows provider/HTTP/code + "Fix in Settings →" link.
- [x] **A2.4** `chat()` wrapped in `withContext(Dispatchers.IO)`.
- [x] **A2.5** `executeWithRetry` retries once on 429/5xx with 800ms backoff.
- [x] **A2.6** `DiagnosticsScreen` (last 200 calls, in-memory `ApiCallLog`). Reachable from Settings top bar.

### A3. More providers + custom endpoints
- [x] **A3.1** Kept `ApiKeyProvider` enum (low churn) + new `ProviderSpec` sealed class for `Builtin`/`Custom`.
- [x] **A3.2** `CustomEndpointRepository` — JSON file at `workspace/system/custom_endpoints.json` (no Room needed).
- [x] **A3.3** Built-ins added: **Gemini** (OpenAI-compat endpoint), **xAI**, **DeepSeek**, **Mistral**, **Together**, **Cerebras**.
- [x] **A3.4** Settings → "CUSTOM ENDPOINTS" with Add dialog (name / URL / schema / default model / key).
- [x] **A3.5** `AiApiManager.chat()` dispatches by `ProviderSchema` from the spec.
- [ ] **A3.6** _(deferred)_ "Test call" button on each custom endpoint card.

---

## Phase B — Theming

- [x] **B1.1** Define `LightColors` and `DarkColors` Material3 schemes in `presentation/theme/Color.kt`.
- [x] **B1.2** Update `Theme.kt` to switch on a `themeMode: ThemeMode` arg (LIGHT / DARK / SYSTEM).
- [x] **B1.3** Add `themeMode` to `ConfigRepository` (default SYSTEM). _(stored in `ForgeConfig.appearance.themeMode`)_
- [x] **B1.4** Add Settings → Appearance section with three radio buttons.
- [x] **B1.5** Make root composable observe theme changes reactively (StateFlow). _(`MainActivity` collects `ConfigRepository.themeMode`)_

**Phase B status: ✅ shipped — see `PHASE_B_README.md`.**

---

## Phase C — Workspace browser (turn the existing scaffold into a real explorer)

### C1. File explorer
- [x] **C1.1** Implement `WorkspaceViewModel.listDirectory(path)` backed by `SandboxManager.listFiles()`.
- [x] **C1.2** Render a tree/list view in `WorkspaceScreen` with breadcrumb navigation.
- [x] **C1.3** Sort options: name / date / size / type.
- [x] **C1.4** Search box that filters current dir + recursive option.

### C2. Inline viewer / editor
- [x] **C2.1** New `FileViewerScreen` route.
- [x] **C2.2** Detect file type by extension; route to text / image / binary viewer.
- [x] **C2.3** Text viewer: monospace TextField, basic syntax color for `.json` / `.md` / `.py` / `.kt` / `.yaml`.
- [x] **C2.4** Image viewer: Coil rendering, pinch-to-zoom.
- [x] **C2.5** Binary viewer: size + hex preview + "Open with…" Android intent.
- [x] **C2.6** Save button → `SandboxManager.writeFile()`. Confirm dialog if outside sandbox.

### C3. Quick actions
- [x] **C3.1** New file / new folder dialogs.
- [x] **C3.2** Rename, move, delete (with trash bin under `workspace/.trash/`).
- [x] **C3.3** Long-press → share via Android intent.
- [x] **C3.4** Multi-select with bulk delete / move.

**Phase C status: ✅ shipped — see `PHASE_C_README.md`.**

---

## Phase D — Domain UIs

### D1. Tools & permissions
- [x] **D1.1** New `ToolsScreen` listing every tool from `ToolRegistry` with name, description, required role.
- [x] **D1.2** Per-tool toggle: enabled / disabled (persisted in `ConfigRepository`).
- [ ] **D1.3** Per-tool grant policy: Always allow / Ask each time / Deny.
- [x] **D1.4** Audit log tab: every tool invocation with timestamp, args, result. Backed by a new Room table.
- [x] **D1.5** "Test tool" button that runs the tool with sample args inside the sandbox.

### D2. Plugin UI
- [x] **D2.1** `PluginsScreen` listing installed plugins from `PluginRepository` (status: active / disabled / failed).
- [x] **D2.2** Detail screen: manifest viewer, declared capabilities, source view (read-only), enable/disable toggle, uninstall.
- [x] **D2.3** Install button → file picker for `.zip` plugin bundles → runs through `PluginValidator` before activating.
- [ ] **D2.4** Permission consent dialog before activating any plugin (lists what it can access).
- [ ] **D2.5** "Plugin marketplace" placeholder tab (curated remote registry — defer implementation).

### D3. Cron UI
- [x] **D3.1** `CronScreen` listing jobs from `CronRepository`: name, expression, next/last run, status.
- [ ] **D3.2** Job detail: edit expression with human-readable preview, edit task body, execution history with logs.
- [ ] **D3.3** "New cron" wizard with templates (daily / weekly / hourly / custom).
- [x] **D3.4** Manual "Run now" button per job.
- [x] **D3.5** Pause / resume toggle.

### D4. Memory UI (highest trust impact)
- [x] **D4.1** `MemoryScreen` with three tabs: **Daily**, **Long-term**, **Skills**.
- [x] **D4.2** Daily tab: chronological log, search.
- [x] **D4.3** Long-term tab: list of facts with source / confidence / last-referenced; tap to edit, swipe to delete.
- [x] **D4.4** Skills tab: stored procedures with name, trigger, body — editable.
- [x] **D4.5** Global search across all three tiers.
- [ ] **D4.6** "Wipe all memory" with biometric confirmation.
- [ ] **D4.7** Export memory to encrypted `.forge-memory` file; import on demand.

### D5. Agents (sub-agents) UI
- [x] **D5.1** `AgentsScreen` listing entries from `SubAgentRepository`.
- [ ] **D5.2** Detail screen: edit identity, system prompt, allowed tools, model preference, memory scope.
- [x] **D5.3** "Spawn agent" form.
- [x] **D5.4** Conversation log per agent.
- [ ] **D5.5** Delegation rules: route task type X to agent Y.

### D6. Projects UI
- [x] **D6.1** Define `Project` model: name + slug + folder under `workspace/projects/<slug>/` + `project.json` (scoped agent, scoped tools, scoped memory).
- [x] **D6.2** `ProjectsScreen` listing all projects with quick stats.
- [x] **D6.3** Project detail: rename, delete, configure scope (which tools / agent / memory tier are visible).
- [ ] **D6.4** "Open project" focuses chat, file browser, memory, and tool scope onto that project.
- [ ] **D6.5** Project switcher in app top bar.

### D7. Skills UI
- [x] **D7.1** `SkillsScreen` (could share code with D4.4 but as a power-user surface).
- [ ] **D7.2** "Train a skill" recorder: capture sequence of tool calls + parameters, name + trigger phrase, save as JSON recipe under `workspace/memory/skills/`.
- [x] **D7.3** Edit / test / disable.
- [x] **D7.4** Export / import as `.skill.json`.
- [ ] **D7.5** "Suggested skills" — analyze recent tool usage and propose recipes.

---

## Phase E — Quality & infrastructure (pick from menu)

| Tag | Item | Status |
|---|---|---|
| **E1** | Streaming responses (SSE for OpenAI-compat, native for Claude) | `[~]` chunked-emit UX shipped (Phase E) |
| **E2** | Token + cost meter (per-message footer + Stats screen) | `[x]` Phase E + Phase F (per-model stats + price editor) |
| **E3** | Multi-conversation history with auto-titles | `[x]` switcher UI shipped in Phase F |
| **E4** | Backup / restore — `.forge` archive of workspace + config + keys (encrypted) | `[ ]` |
| **E5** | Biometric gate on keys, ADMIN tools, Memory UI | `[ ]` |
| **E6** | Offline fallback to Ollama or on-device Gemma 2B (MediaPipe) | `[ ]` |
| **E7** | Voice in/out — `SpeechRecognizer` + `TextToSpeech`, hands-free toggle | `[ ]` |
| **E8** | Notification quick-reply | `[ ]` |
| **E9** | Home-screen widget (last reply + quick prompt) | `[ ]` |
| **E10** | Code-block "Run" buttons (Python → sandbox, bash → ShellExecutor) | `[ ]` |
| **E11** | Per-conversation system prompt overrides | `[ ]` |
| **E12** | Local crash log file (no remote telemetry) | `[ ]` |
| **E13** | R8 + signed release CI pipeline | `[ ]` |

---

## Phase F — New ideas worth considering (beyond the original ask)

These weren't in the original list but would meaningfully improve the product:

| Tag | Item | Why | Status |
|---|---|---|---|
| **F1** | **Snapshots / undo for config mutations** | Agent can mutate its own config — needs an audit log + one-tap rollback. Critical safety. | `[x]` workspace zip snapshots + Snapshots screen shipped |
| **F2** | **Sandboxed plugin marketplace with signature verification** | Future-proof D2.5 with signed manifests and an allow-list of publishers. |
| **F3** | **Structured tool output rendering** | When a tool returns a table, image, or chart, render it natively in chat instead of as JSON text. |
| **F4** | **Embeddings index for memory retrieval** | Today memory is keyword-matched; vector similarity drastically improves relevance. Use ONNX MiniLM on-device. |
| **F5** | **MCP (Model Context Protocol) client** | Let Forge consume tools from any MCP server (huge ecosystem). Position Forge as the mobile MCP client nobody has built yet. | `[x]` HTTP JSON-RPC transport (`tools/list` + `tools/call`) + MCP Servers screen shipped |
| **F6** | **Shared key brokerage via Replit AI Integrations** | For users who don't want to BYOK, optionally route through Replit's proxy (no key needed). |
| **F7** | **Data-residency selector for cron jobs** | "Run this job only on Wi-Fi" / "only when charging" / "only at night." Already supported by WorkManager — just expose it. |
| **F8** | **Conversation export** (Markdown / JSON / shareable link) | Trivial, high-value. |
| **F9** | **Inline image input** (Camera / Gallery → multimodal models) | Gemini, Claude 3.5, GPT-4o all accept images. |
| **F10** | **Privacy dashboard** | One screen showing exactly what data left the device, when, and to which provider. |

---

## Recommended milestone schedule

| Milestone | Contents | Effort |
|---|---|---|
| **M0 — Carry-over** | F1 / F2 / F3 (Python init fix) | < 1 hour |
| **M1 — Unblock chat** | A1, A2, A3 | 3–5 days |
| **M2 — Theme** | B1 | 1 day |
| **M3 — Workspace browser** | C1, C2, C3 | 4–6 days |
| **M4 — Trust UIs** | D1 (tools), D4 (memory) | 1 week |
| **M5 — Productivity UIs** | D2 (plugins), D3 (cron) | 1 week |
| **M6 — Agents & projects** | D5, D6 | 1–2 weeks |
| **M7 — Skills + polish** | D7, E1 (streaming), E2 (tokens) | 1 week |
| **M8 — Hardening** | E5 (biometric), E4 (backup), E13 (signed release) | 1 week |
| **M9 — Differentiators** | F5 (MCP client), F4 (embeddings), F9 (multimodal) | 2 weeks |

---

## Progress log

> Append a short note when you complete a milestone, with date and commit hash. Keeps a quick read of where things stand.

| Date | Milestone | Note |
|---|---|---|
| 2026-04-22 | M0 | F1 + F2 already applied in zip `_1776856880259`. Awaiting hardware confirmation. |
| 2026-04-22 | M1 (Phase A) | Drop delivered at `exports/forge-os-phase-A/`. 11 files (4 new, 7 replace). 6 new providers + custom endpoints + diagnostics + model picker + typed errors. |
| 2026-04-22 | M2 (Phase B) | Theming drop. ThemeMode enum + reactive `MainActivity` re-theme + Settings appearance card. Persisted in `ForgeConfig.appearance.themeMode`. |
| 2026-04-22 | M3 (Phase C) | Workspace browser drop. Explorer (breadcrumb / sort / search / recursive), viewer/editor (text + image + binary, syntax colour, pinch-zoom, hex dump, Open with…), quick actions (new / rename / delete-to-trash / share / multi-select). Adds `coil-compose:2.5.0` and `FileProvider`. |
| 2026-04-22 | M7-ish (Phase E) | CostMeter + per-call cost pill, ConversationRepository persistence, HeartbeatMonitor health dot, SkillRecorder + `save skill` local command. |
| 2026-04-22 | Phase F | F1 workspace snapshots + screen + 4 tools, F5 MCP HTTP client + repo + screen + tool dispatch, E2 cost stats screen with per-model breakdown + price editor, E3 multi-conversation switcher screen. ToolRegistry / Hub / Nav graph wired. |
| | | |

---

*Document version: Part 2 — generated against `forge-os-master_1776856880259`. Update the checkboxes as work progresses; commit this file alongside the code so the team always knows current state.*
