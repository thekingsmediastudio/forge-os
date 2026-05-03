# Forge OS — Fixes Applied

This is a patched copy of the upload `forge-os-master_(4)_1777008887251.zip`.
The fixes are surgical — no architecture changes — and target the agent
behavior issues reported in chat.

---

## 1. `parseArgs` crash → "X required" everywhere

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
(`parseArgs`, ~line 1100)

**Bug.** `parseArgs` did `value.jsonPrimitive.content` for every entry. The
moment the model emitted a non-primitive value (object, array, null, or
any nested structure), the call threw `IllegalArgumentException` and the
**entire** argument map fell back to empty. The downstream tool then
reported `Error: path required` (or `name required`, or `code required`,
…) even though the model had supplied them.

This is what made `file_write`, `cron_add`, and several other tools look
randomly broken.

**Fix.** Per-entry try/catch, with explicit handling of `JsonNull`,
`JsonPrimitive`, `JsonArray`, and `JsonObject` — primitives become their
content string, arrays/objects are kept as their JSON text. One bad
entry no longer wipes the whole map.

---

## 2. `cron_add failing`

Same root cause as #1. With `parseArgs` fixed, `cron_add` now receives
its `name`/`schedule`/`payload` arguments correctly.

---

## 3. `browser_get_html` requires the Browser screen to be open

**Files:**
- `app/src/main/java/com/forge/os/data/web/HeadlessBrowser.kt` (NEW)
- `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
  (`browserNavigate`, `browserGetHtml`)

**Bug.** The original implementation only spoke to `BrowserSessionManager`,
whose live `WebView` is owned by the `BrowserScreen` composable. If the
user wasn't on that screen, the agent's `browser_get_html` call timed
out waiting for a snapshot that never arrived.

**Fix.** Added `HeadlessBrowser`, which owns its own off-screen
`WebView` on the main thread and resolves on `onPageFinished` +
short settling delay. `browser_navigate` now ALSO renders the page
headlessly and caches it. `browser_get_html`:

1. If you pass `url=...`, renders that URL fresh in the headless WebView.
2. Otherwise tries the live on-screen WebView (short 2.5 s timeout).
3. Falls back to the cached headless render from the most recent navigate.

Result: the agent can browse without the user touching the Browser tab.

---

## 4. `http_fetch` / `curl_exec` / `ddg_search` / `composio_call` failing

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`

**Bug.** All of these did synchronous OkHttp calls directly on whatever
thread invoked them. On Android that is `NetworkOnMainThreadException`
the moment they get scheduled on the main dispatcher (the agent loop is
launched from a coroutine that can resume there). They also reported
errors as `e.message`, which for that exception is `null` — so the user
saw `❌ http_fetch failed: null`.

**Fix.** All four are now `suspend` and wrapped in
`withContext(Dispatchers.IO)`. Errors include the exception class name,
e.g. `❌ http_fetch failed: NetworkOnMainThreadException: null`, which
makes the next failure debuggable.

`composio_call` additionally reads `composio_api_key` from
`memoryManager` if not passed in args.

---

## 5. `file_write` "path required" even when path was given

Same root cause as #1 (parseArgs).

---

## 6. ReAct agent loses context

**File:** `app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt`

**Fix.** Rewrote the system prompt with explicit numbered rules that
hammer on:

- "CONTEXT IS PRECIOUS": re-read prior tool outputs before pivoting,
  don't re-fetch what you already fetched.
- "STAY ON TASK": finish what was asked, don't drift.
- "PASS ARGUMENTS CORRECTLY": `path` and `content` are strings, never a
  nested object.
- Explicit pointers to `config_write` for config changes and
  `project_serve` for "serve / host / preview" intents.

---

## 7. Tool descriptions too terse

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
(`ALL_TOOLS`)

Enriched the descriptions for the high-traffic tools — `file_read`,
`file_write`, `file_list`, `file_delete`, `shell_exec`, `python_run`,
`config_read`, `config_write`, `config_rollback`, `cron_add`,
`http_fetch`, `curl_exec`, `ddg_search`, `browser_navigate`,
`browser_get_html`, `project_serve`. Each now has a WHEN-TO-USE
paragraph and concrete examples.

---

## 8. "Change config" intercepted by hard-coded keyword check

**File:** `app/src/main/java/com/forge/os/presentation/screens/ChatViewModel.kt`

**Bug.** `isConfigCommand()` was a hand-rolled keyword check that only
fired on a tiny set of phrasings. Anything the agent (or user) said
slightly differently was passed to the LLM but the LLM had no
instruction to call `config_write`, so the request died.

**Fix.** Removed the auto-route. Config requests now flow through the
agent, which (per its enriched system prompt and `config_write` tool
description) calls `config_write` with the user's verbatim request.

---

## 9. Agent doesn't auto-call `project_serve`

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
(tool definition for `project_serve`) plus rule 6 in the new ReAct
system prompt.

**Fix.** The tool description now explicitly enumerates the trigger
phrases ("serve", "host", "preview in browser", "open this on my
laptop", "share over wifi") and tells the agent to default to the
recently-written project folder. The system prompt also surfaces this.

---

## 11. Fully-headless agent browser (no need to open the Browser tab)

**Files:**
- `app/src/main/java/com/forge/os/data/web/HeadlessBrowser.kt` (rewritten)
- `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`
  (`browser_navigate`, `browser_get_html`, `browser_eval_js`,
  `browser_fill_field`, `browser_click`, `browser_scroll`)

**What changed.** `HeadlessBrowser` is now a true persistent stateful
browser owned by the agent. One `WebView` is created once, reused
across calls, and exposes:

- `navigate(url)` — loads and waits for `onPageFinished`.
- `getRawHtml()` / `getReadableText()` — read the current DOM.
- `evalJs(script)` — run JS, return the value.
- `fillField(selector, value)` — sets a form input AND dispatches
  `input` + `change` events so React/Vue notice.
- `click(selector)` — `.click()` with a synthetic `MouseEvent`
  fallback for elements that don't expose one natively.
- `scroll(x, y)` — `window.scrollTo`.
- `reset()` — discard the WebView (next op creates a new one).

All six agent tools (`browser_navigate`, `browser_get_html`,
`browser_eval_js`, `browser_fill_field`, `browser_click`,
`browser_scroll`) now route to this persistent off-screen WebView
instead of the on-screen `BrowserSessionManager`. The user's visible
Browser tab is left completely alone — the user can keep browsing in
their own tab while the agent works invisibly.

**Cookies / sessions are shared** with the on-screen browser via
Android's global `CookieManager` (`setAcceptCookie(true)` +
`setAcceptThirdPartyCookies(headlessWebView, true)`), so logging into
a site once on the visible tab automatically gives the agent the same
session.

**State persists** across calls. The agent can navigate, scroll, fill
a form, click submit, and then read the result page — all without the
WebView being torn down between steps.

The ReAct system prompt rule #7 was updated to spell this out.

---

## 10. `ConfigMutationEngine` patterns too narrow + lower-cased input

**File:** `app/src/main/java/com/forge/os/domain/config/ConfigMutationEngine.kt`

**Bug.** The original lowercased the entire request, then extracted the
agent name from the lowercase string — so "rename to Forge Mk2" became
`forge mk2`. Patterns also missed common phrasings ("rename", "switch
to", "use claude", "set default model").

**Fix.** Rewrote with case-preserving extraction for value fields
(agent name, model name, greeting), broader regex coverage, and
helpful error messages listing examples. Newly recognised intents:

- switch default provider (`use anthropic`, `switch to openai`)
- switch default model (`use model claude-sonnet-4-20250514`)
- multi-tool enable/disable
- route any task type to provider+model
- greeting changes
- heartbeat without unit suffix

The tool-name extractor now knows about all currently-registered tools,
not just the original ~18.

---

## 12. Git tools could crash the app on edge cases

**File:** `app/src/main/java/com/forge/os/data/git/GitRunner.kt`

**Bug.** Only `status`, `log`, `diff` and `branch` were wrapped in
`runCatching {}.getOrElse { friendlyError(...) }`. The other ops —
`init`, `add`, `commit`, `checkout`, `remoteSet`, `clone`, `push`,
`pull` — let raw JGit / JNI / IO exceptions propagate out of the
`suspend` function. The outer try/catch in `ToolRegistry.dispatch`
caught most of them but produced ugly `Error: null` / `Error:
RepositoryNotFoundException` strings that confused the model, and a
few JGit native errors leaked through coroutine cancellation paths
straight to the crash reporter.

**Fix.** Every git op now has the same `runCatching { … }.getOrElse
{ friendlyError(it, dir) }` wrapper, so the agent sees the exact
same friendly format ("(no commits yet — run git_commit first)",
"❌ Not a git repository: …", "❌ git: …") regardless of which sub-
command tripped the error. No JGit exceptions reach the crash
reporter via the agent path anymore.

---

## 13. New Telegram session / allow-list tools

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`

The agent could send and toggle channels but had no way to ask
"which Telegram chat is talking to me right now?" or to grow / shrink
the per-channel allow-list (`ChannelConfig.allowedChatIds`). Added six
new tools, all built on top of the existing `ChannelManager` surface:

- `telegram_main_chat`  — returns the chat id of the current session,
  reading the `InputRoute` coroutine context first (when an agent run
  was kicked off by a Telegram message), then the most-recent inbound
  for the channel, then the channel's `defaultChatId`.
- `telegram_list_chats` — recent (channel, chat) pairs, newest first.
- `telegram_get_allowed_chats` — show the allow-list (empty = allow all).
- `telegram_set_allowed_chats` — replace the allow-list with a CSV.
- `telegram_allow_chat` — add one chat id (deduped).
- `telegram_deny_chat`  — remove one chat id.

When `channel_id` / `channel` are omitted these helpers auto-pick the
most recently active Telegram channel, so a single-bot setup is
zero-config.

---

## 14. New `app_describe` self-description tool

**File:** `app/src/main/java/com/forge/os/domain/agent/ToolRegistry.kt`

Until now the agent was unaware of its own host: it didn't know it
was running inside Forge OS, that the workspace lives under
`/sdcard/Android/data/com.forge.os/files/workspace/`, that the app
ships its own HTTP API (`ForgeHttpServer`, default port 8789) and
that other apps on the device can bind to its AIDL service
(`com.forge.os.api.IForgeOsService`) for `invokeTool` / `askAgent` /
`getMemory` / etc.

`app_describe` returns a markdown summary covering all of the above
plus a live capability snapshot (running tool count, server status,
Bearer-key prefix, configured channels). The agent should call it
when the user asks "what is this app?", "what can you do?", "do you
have an API?", or whenever it needs to ground itself in the
environment.
