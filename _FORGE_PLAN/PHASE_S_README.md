# Phase S — Plumbing, Permissions, Git, Downloads, Routing UI, Workspace Orderliness

**Snapshot:** post‑Phase R (2026-04-24)

Phase S is a single, focused plumbing pass that closes the
"configured-but-not-actually-usable" gaps surfaced after Phases Q and R.
It adds **no new product surface** and **no new permissions**; it makes the
existing surface actually behave the way the README and onboarding already
claim it does.

---

## Status in this snapshot

| Item | Implemented in this zip? | Notes |
| --- | --- | --- |
| **S2** Android tools enabled by default | ✅ done | `ForgeConfig.enabledTools` extended; system prompt rule #13 added. |
| **S3** Companion uses fallback chain | ✅ done | `EmotionalContext` + `ConversationSummarizer` switched from `chat()` to `chatWithFallback()`. |
| **S8** Workspace orderliness | ✅ done | New `WorkspaceLayout.kt`, expanded folder set in `SandboxManager`, new `workspace_describe` tool, system prompt rule #14. |
| **S1** Git tools (JGit) | 📋 planned | Spec below. Needs JGit dependency + handler cases. |
| **S4** Settings → Model Routing screen | 📋 planned | Spec below. Large UI surface; left for a focused UI pass. |
| **S5** Tools "advanced overrides" sheet | 📋 planned | Spec below. Depends on S6's permission additions. |
| **S6** `file_download` / `browser_download` | 📋 planned | Spec below. Needs streaming download + cookie reuse. |
| **S7** Docs / onboarding refresh | 📋 partial | This file is the single source of truth for now; `README.md` and `OnboardingScreen` will be updated when S1/S4/S5/S6 land. |

---

## Why this phase exists (root causes)

The user-visible symptoms below all trace back to a small number of concrete
defects. Each item in §What's in Phase S maps to one of these:

1. **Git tools are advertised but unimplemented.** `git_init`, `git_commit`,
   `git_status` are listed in `ToolRegistryConfig.enabledTools` but
   `ToolRegistry.executeInternal` has no `case` for them and there is no
   `org.eclipse.jgit` (or shell git) dependency. The agent's call falls
   through to "unknown tool" and the user sees "claims it doesn't exist".
2. **Android tools were wired but disabled by default.** All `android_*`
   handlers exist in `ToolRegistry` and `AndroidController.kt` is fully
   implemented, but the default `ToolRegistryConfig.enabledTools` list
   omitted every `android_*` entry. The permission gate blocked them, so
   the agent correctly answered "I don't have access" — even though the
   user assumed they were on. **Fixed in this snapshot (S2).**
3. **Companion mode bypassed the global fallback chain.**
   `EmotionalContext.kt` and `ConversationSummarizer.kt` called
   `apiManager.chat(...)` directly instead of `chatWithFallback(...)`, so
   Companion turns died on a rate-limited primary key while everything else
   survived. **Fixed in this snapshot (S3).**
4. **No UI to edit the global model routing / fallback chain.**
   `ModelRoutingConfig.fallbackChain` exists in `ForgeConfig` but Settings
   only edits `compactMode.enabled`. The user can't decide which provider
   cron / alarms / background prompting / sub-agents fall through to.
5. **"Padlock you can't open."** The Tools screen exposes only **enable**
   and **require‑confirm** switches. `PermissionManager` enforces
   additional hard policies (`blockedExtensions`, `blockedHosts`,
   `blockedConfigPaths`, per-tool `blockedPatterns`) that the UI never
   surfaces. Tools look on but still fail; flipping the switch can't
   unlock them.
6. **No download tool.** The agent can `http_fetch` text and
   `browser_screenshot_region` an area, but it cannot stream a binary file
   into the workspace. Users keep asking for "download this for me" and
   getting a base64 dump or a refusal.
7. **Workspace was a junk drawer.** Files ended up at the root, in
   `temp/` when they shouldn't be, or duplicated across folders, because
   the agent had no canonical "where does this go" reference and the
   sandbox only created six folders by name with no purpose annotation.
   **Fixed in this snapshot (S8).**

---

## What's in Phase S

### S1 — Implement the git tools  *(planned)*

**Files added**
- `app/src/main/java/com/forge/os/data/git/GitRunner.kt`

**Files edited**
- `app/build.gradle` — add `implementation 'org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r'`
- `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
  - inject `GitRunner` into the constructor
  - add `git_init`, `git_status`, `git_commit`, `git_log`, `git_diff`,
    `git_branch`, `git_checkout`, `git_remote_set`, `git_clone`, `git_push`,
    `git_pull` cases in `executeInternal`
  - add matching `tool(...)` definitions in `ALL_TOOLS`
- `app/src/main/java/com/forge/os/domain/config/ForgeConfig.kt`
  - extend `confirmDestructive` to include `git_push`, `git_checkout`,
    `git_clone` (over network), and `git_pull --force`-style flags

**Token retrieval order:** `args.token` → `memory_recall("git_credentials/<host>")`
→ `request_user_input` (last resort).

**Out of scope for S1:** SSH keys (HTTPS+token only), submodules, LFS.

---

### S2 — Enable Android tools by default  *(✅ done in this snapshot)*

**Files edited**
- `app/src/main/java/com/forge/os/domain/config/ForgeConfig.kt` — added to
  default `enabledTools`:
  - `android_device_info`, `android_battery`, `android_volume`,
    `android_network`, `android_storage`, `android_screen`,
    `android_snapshot`, `android_list_apps`
- Still behind `requiresConfirmation = true` by default:
  - `android_set_volume`, `android_launch_app`
- `app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt` — added
  rule **#13**:
  > "DEVICE STATE. The phone-state tools are ON by default … Use them when
  > the user asks anything about the phone. Don't say 'I can't access
  > that' — try `android_snapshot` first."

**Capabilities now live**
- "What's my battery?", "Am I on Wi‑Fi?", "How much storage do I have?",
  "What apps are installed?", "Open Spotify" — all work without the user
  toggling anything in Tools.
- Volume changes and app launches still prompt for confirmation.

---

### S3 — Companion uses the global fallback chain  *(✅ done in this snapshot)*

**Files edited**
- `app/src/main/java/com/forge/os/domain/companion/EmotionalContext.kt`
  — replaced `apiManager.chat(...)` with `apiManager.chatWithFallback(...)`.
- `app/src/main/java/com/forge/os/domain/companion/ConversationSummarizer.kt`
  — same replacement.

**Capability now live**
- Companion turns survive a dead/rate-limited primary key the same way
  agent turns already do. No more "Companion silently stops talking
  while the rest of the app works."

---

### S4 — Settings → Model Routing editor  *(planned)*

**Files added**
- `app/src/main/java/com/forge/os/presentation/screens/settings/ModelRoutingScreen.kt`
- `app/src/main/java/com/forge/os/presentation/screens/settings/ModelRoutingViewModel.kt`

**Files edited**
- `app/src/main/java/com/forge/os/presentation/screens/SettingsScreen.kt`
  — add a "Model routing" row that opens the new screen.
- `app/src/main/java/com/forge/os/presentation/MainActivity.kt`
  — wire the `modelRouting` route.

**Screen contents**
1. **Primary** — provider + model dropdown (saved to
   `modelRouting.primary`, `modelRouting.primaryModel`).
2. **Fallback chain** — drag-reorderable list of `ProviderModelPair`
   entries with add/remove. Greys out chain entries with no saved key.
3. **Per-task overrides** — three rows for `chat`, `code`, `embedding`
   (ties into `ModelRoutingConfig.taskOverrides`).
4. **Background callers section** — checkboxes for "Cron uses chain",
   "Alarms (PROMPT_AGENT) uses chain", "Sub-agents use chain",
   "Proactive turns use chain". All default `true`. Persisted under
   `modelRouting.backgroundUsesFallback`.
5. **Compact mode toggle** — moved here from the main Settings page.

---

### S5 — Tools screen: advanced overrides ("padlock unlock")  *(planned)*

**Files added**
- `app/src/main/java/com/forge/os/presentation/screens/tools/AdvancedOverridesSheet.kt`

**Files edited**
- `app/src/main/java/com/forge/os/presentation/screens/tools/ToolsScreen.kt`
  — add a small lock icon next to each tool with non-empty
  `blockedPatterns` / `blockedExtensions` / `blockedHosts` /
  `blockedConfigPaths`. Tap → opens the sheet.
- `app/src/main/java/com/forge/os/presentation/screens/tools/ToolsViewModel.kt`
  — expose the four lists per tool, edit endpoints, and a
  "user override locked" pill so the agent's `control_set` calls cannot
  silently widen them.
- `app/src/main/java/com/forge/os/domain/security/PermissionManager.kt`
  — read overrides from `ForgeConfig.permissions.userOverrides` first,
  fall back to defaults.
- `app/src/main/java/com/forge/os/domain/config/ForgeConfig.kt` — add
  `userOverrides: ToolPolicyOverrides` to the permissions block.

**Sheet contents per tool**
- Editable lists for blocked patterns / extensions / hosts / config paths.
- Per-row "remove" + bulk "reset to defaults".
- A toggle: **"Agent may modify these overrides"** (default OFF).

---

### S6 — Download capability  *(planned)*

**Files added**
- `app/src/main/java/com/forge/os/data/net/DownloadManager.kt` — pure
  Kotlin OkHttp streaming with content-length progress reporting (does
  **not** use Android's system DownloadManager — keeps everything in the
  sandbox).
- `app/src/main/java/com/forge/os/data/net/MimeSniffer.kt` — small helper
  for filename and extension inference.

**Files edited**
- `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
  - new `file_download {url, save_as?, max_bytes?, headers?}` — streams
    a URL into `downloads/`, returns `{path, bytes, sha256, mime}`.
    Default cap from `sandboxLimits.maxDownloadBytes` (defaults to 200 MiB).
  - new `browser_download {selector?, url?, save_as?, max_bytes?}` — uses
    the headless WebView's cookies, so logged-in pages work.
  - extend `file_upload_to_browser` description to mention pairing with
    `file_download` for download → re-upload flows.
- `app/src/main/java/com/forge/os/domain/security/PermissionManager.kt`
  — add `blockedExtensions` and `blockedHosts` enforcement to download
  paths (so an apk download still hits the same lock the file tools do).
- `app/src/main/java/com/forge/os/data/web/HeadlessBrowser.kt` — expose
  a `getCookieHeader(url)` helper for `browser_download` to reuse.
- `app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt` — add
  rule **#15** (downloads).

---

### S7 — Documentation refresh  *(partial — this file)*

This document is the canonical Phase S spec for now. Once S1/S4/S5/S6 land,
also update:

- `README.md` — add Git, Downloads, Model Routing UI, and the corrected
  default-tools statement to the highlights table.
- `app/src/main/java/com/forge/os/presentation/screens/OnboardingScreen.kt`
  — add Git / Downloads / Routing / Advanced overrides cards.

---

### S8 — Workspace orderliness  *(✅ done in this snapshot)*

The agent now treats the workspace as a structured filesystem with a
canonical layout, not a junk drawer.

**Files added**
- `app/src/main/java/com/forge/os/domain/workspace/WorkspaceLayout.kt`
  — single source of truth for top-level folder names, their purpose,
  and the routing rules ("a network download goes in `downloads/`",
  "a project goes in `projects/<name>/`", etc).

**Files edited**
- `app/src/main/java/com/forge/os/data/sandbox/SandboxManager.kt`
  — expanded the init folder list from 10 entries to 15 (added
  `downloads`, `alarms`, `snapshots`, `notes`, `exports`) with a
  one-line purpose comment per folder.
- `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
  - new `workspace_describe` tool that returns the full
    `WorkspaceLayout.describe()` text, including routing rules.
  - registered in `ALL_TOOLS` with a WHEN-TO-USE that explicitly tells
    the model to call this before any uncertain `file_write`.
- `app/src/main/java/com/forge/os/domain/config/ForgeConfig.kt`
  — added `workspace_describe` to the default `enabledTools` list.
- `app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt` — added
  rule **#14**:
  > "WORKSPACE ORDERLINESS. … NEVER write a file to the workspace root.
  > NEVER invent new top-level folders. If you're unsure where a file
  > belongs, call `workspace_describe` once at the start of the task."

**Capabilities now live**
- The agent has fifteen named, purpose-tagged top-level folders to
  choose from instead of inventing names.
- "Download this PDF" lands in `downloads/` (when S6 ships); for now
  any deliberate write through `file_write` lands in the right folder
  because the model now has a reference to consult.
- A new project always becomes `projects/<project_name>/` instead of
  scattering files at the root.
- Reserved folders (`memory/`, `plugins/`, `snapshots/`, `system/`,
  `alarms/`, `agents/`, `heartbeat/`, `cron/`) are explicitly off-limits
  to direct `file_write`; the agent uses each subsystem's tool instead.
- The `workspace_describe` tool gives the model an in-conversation
  refresher whenever it loses track.

**Why this is in S, not later:** every other Phase S item assumes a
predictable file layout (downloads land in `downloads/`, the routing
editor's chain export lands in `exports/`, the override sheet's audit
trail lands in `system/`). Establishing the layout first means the
later items can rely on it.

---

## Capabilities matrix (what works now vs. before, and what's planned)

| Capability | Pre‑S | This snapshot | Post full Phase S |
| --- | --- | --- | --- |
| Agent runs git locally | ❌ unimplemented | ❌ unchanged | ✅ via JGit (S1) |
| Agent reads battery/Wi‑Fi/storage without manual toggle | ❌ permission‑gated | ✅ on by default | ✅ |
| Companion survives a dead primary key | ❌ direct `chat()` | ✅ `chatWithFallback()` | ✅ |
| User edits provider fallback chain in UI | ❌ code only | ❌ unchanged | ✅ Settings → Model routing (S4) |
| Cron/alarms/sub-agents share the chain | ⚠ partial | ⚠ partial | ✅ explicit toggles, default ON (S4) |
| User unlocks a blocked host / extension | ❌ source edit | ❌ unchanged | ✅ Tools → padlock → sheet (S5) |
| Agent can silently widen those locks | ⚠ via `control_set` | ⚠ via `control_set` | ❌ user lock blocks it (S5) |
| Agent downloads a PDF / CSV / ZIP | ❌ | ❌ | ✅ `file_download` (S6) |
| Agent downloads a logged-in page asset | ❌ | ❌ | ✅ `browser_download` (S6) |
| Workspace has a canonical layout the agent respects | ❌ | ✅ 15 named folders + `workspace_describe` + rule #14 | ✅ |
| README + onboarding mention the new surface | ❌ | ⚠ this file only | ✅ (S7) |

---

## Sequencing & risk (for the still-planned items)

**Order (to minimise rebase pain):**
S1 → S6 → S5 → S4 → S7.
(S2, S3, S8 already shipped above.)

**Why:** S1 and S6 are additive in `ToolRegistry`/`ForgeConfig` and don't
touch UI. S5 depends on S6's new permission entries. S4 is the largest
UI piece. S7 documents the final state.

**Risk hot-spots**
- JGit pulls in BouncyCastle on some configs; verify R8/proguard rules.
- `browser_download` cookie reuse needs the on-screen WebView's
  `CookieManager` — confirm `HeadlessBrowser` and `BrowserSessionManager`
  share `CookieManager.getInstance()` (they currently do).
- The advanced overrides sheet must NOT be reachable from `control_set` —
  only from a human tap. Audit log this distinction.
- Default-enabling the read-only `android_*` tools should NOT
  retroactively enable previously-disabled installs of the app — the
  merge logic in `ConfigRepository.migrate(...)` should respect a user
  who explicitly disabled them.

---

## Out of scope for Phase S

- Switching providers per-message in chat (`/use openai` slash command).
- A separate "background usage" budget cap (Phase T candidate).
- Voice tool calls.
- iOS / desktop port concerns (this stays Android-only).
- A diff viewer for git_diff output (Phase T candidate).
