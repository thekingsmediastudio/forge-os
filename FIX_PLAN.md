# Forge OS — Bug Fix Patch & Plan (Phase U2)

This patch addresses the 6 bugs you reported. Items **(1)–(6)** below each
have a *root cause*, *applied fix*, and *manual verification* section. All
file edits are concrete patches in this checkout; nothing is mocked.

> **Files touched (all under `app/src/main/java/com/forge/os/`):**
> - `app/build.gradle`
> - `presentation/screens/FileViewerViewModel.kt`
> - `presentation/screens/BrowserScreen.kt`
> - `presentation/screens/channels/ChannelSessionViewScreen.kt`
> - `presentation/screens/channels/ChannelsViewModel.kt`
> - `domain/channels/ChannelManager.kt`
> - `domain/agent/ToolRegistry.kt`

---

## (1) In-app browser crashes on open

**Root cause.** `BrowserScreen` constructs `WebView(ctx)` directly inside the
`AndroidView` factory with no guard. Two well-known constructor failures kill
the activity:

1. **`MissingWebViewPackageException`** — the system WebView package is being
   updated by Play Store, has been disabled by the user, or is on a corrupted
   profile.
2. **"Using WebView from more than one process at once with the same data
   directory"** — Android Pie+ requirement; happens when a secondary process
   (e.g. Chaquopy worker, isolated services) instantiates a WebView before the
   main process.

**Fix applied (`BrowserScreen.kt`).**
- Set a per-PID `WebView.setDataDirectorySuffix("forge_<pid>")` on Pie+ to
  defuse the multi-process collision.
- Wrap `WebView(ctx)` in `try/catch`. On failure we set Compose state
  `webViewFatal` and return a stub `View(ctx)` placeholder; on the next
  recomposition, `WebViewUnavailablePanel` replaces the WebView with a
  friendly retry panel showing the OS error message.
- `AndroidView` is now typed as `AndroidView<View>` and the `update` lambda
  safe-casts (`v as? WebView ?: return`) so the placeholder branch is type-safe.

**Verification.** Open *Browser* on:
- a clean device — should load `about:blank`,
- a device where `com.google.android.webview` is disabled (Settings → Apps →
  WebView → Disable) — should show the orange "⚠ In-app browser unavailable"
  panel with the underlying OS message and a retry button.

---

## (2) Telegram session view needs a model picker; reply pipeline must use the chosen model first, with fallback to the default route on failure

**Root cause.** `ChannelSessionViewScreen` only showed the timeline. The
runtime `ChannelManager.runAgentReply` already honoured per-channel/per-session
overrides through `sessionStoreOverrides` but had no UI binding *and* had no
fallback — if the chosen provider failed, the whole turn died.

**Fix applied.**
- **`ChannelManager.kt`** — exposed `getSessionModel`, `availableProviders`
  (only providers with a saved key), `defaultModelFor` (suggested model id),
  and refactored `runAgentReply` so the agent run goes through a `runOnce()`
  helper. The chosen `ProviderSpec` is tried first; on `ApiCallException`
  *or* any other `Exception`, we record an `Info` event in the session
  timeline ("Chosen model unavailable … Retrying with default route…") and
  re-run with `spec = null`, which delegates to `ProviderRouter`'s normal
  auto-pick + fallback chain.
- **`ChannelsViewModel.kt`** — proxied the new `ChannelManager` accessors
  so screens can call them without holding a reference to the manager.
- **`ChannelSessionViewScreen.kt`** — added an inline **MODEL** row above
  the timeline with the current override (or "default route — auto-pick +
  fallback") and CHANGE / CLEAR actions. The CHANGE button opens a dialog
  listing every provider that has a key on file; selecting one pre-fills the
  enum's `defaultModel` so the user can just hit USE. Picker disables itself
  when no keys are stored and explains the next step ("Add an API key in
  Settings → Keys, then come back to pick a model").

**Verification.**
- With a Telegram channel running and at least 2 provider keys saved,
  open the session view. The MODEL row says "(default route — auto-pick +
  fallback)". Tap CHANGE, pick `OPENAI` + `gpt-4o-mini`, tap USE. Send a
  message in Telegram; the timeline shows tool calls/responses produced by
  the chosen provider.
- Temporarily revoke the OpenAI key (Settings → Keys → delete OPENAI), keep
  the override pinned, send another Telegram message: the timeline should
  show an `Info` event "Chosen model unavailable…" then the reply produced
  by the default route.

---

## (3) Telegram send tool sends each message twice AND returns an error

**Root cause.** Two independent send paths fire for every model turn:

1. The agent calls the `channel_send` tool itself with the final reply text
   (because the system prompt allowed it).
2. `ChannelManager.runAgentReply` *also* sends `finalText` automatically at
   the end of the turn.

Both paths succeed at the Telegram side, hence two visible messages. The
"error" was the second send returning a stale/duplicate message-id confusion
in the agent's tool log.

**Fix applied.**
- **`ChannelManager.kt`** — added a thread-safe `suppressedAutoReplies`
  set keyed by route key (`channel:<channelId>:<chatId>`). Public method
  `suppressAutoReplyFor(routeKey)` adds; private `consumeAutoReplySuppression`
  removes (one-shot). After the agent run, if the route was marked, we
  *don't* auto-send `finalText` — we only update the conversation history
  and return.
- **`ToolRegistry.channelSend`** — after a successful send, reads
  `currentCoroutineContext()[InputRoute]?.routeKey`, parses out
  `<channelId>:<chatId>`, resolves the destination's channel id (from the
  `channel_id` arg or by name lookup), and if the destination matches the
  active route, calls `channelManager.suppressAutoReplyFor(routeKey)`.
- **`ChannelManager.kt`** — updated the *user-supplied prompt* prefix from
  "do NOT call channel_send to send it" to: "You MAY also call `channel_send`
  explicitly to this same chat … Forge de-duplicates the final auto-reply
  when you do, so the user never sees the same message twice." This is
  forward-compatible with both styles of model behaviour.

**Verification.**
- Force the issue by enabling an OpenAI tool-happy model (or just test
  with OpenRouter/auto). Send a Telegram message. Each visible bot reply
  should arrive exactly once.
- Inspect the session timeline: at most one `OutgoingText` event per turn,
  even if the timeline shows a `ToolCall` for `channel_send` inside the turn.

---

## (4) Verify the bot can change settings & control the plane

**Status: already wired — confirmed by code review; no edits needed beyond (3).**

The Telegram-driven agent loop runs through the same `ToolRegistry` instance
as the on-screen chat, so every tool is available to the bot:

- `apikey_set / apikey_remove / apikey_list` — Settings → Keys edit path.
- `secret_set / secret_remove / secret_list` — named secrets vault.
- `config_get / config_update` — full `ConfigRepository` mutation.
- `channel_set_provider / channel_set_session_provider` — per-channel and
  per-session model overrides (now with the matching session-view UI from
  fix 2).
- `git_*`, `terminal_*`, `code_run_*`, `file_*`, `package_install`,
  `plugin_install/uninstall`, `delegate_task`, `mcp_*` — full control plane.
- `request_user_input` — already routes back to the originating Telegram
  chat via the `InputRoute` coroutine context element.

After fix (3), the bot can also send mid-turn status updates to itself
without triggering the duplicate-send safeguard. There is no longer any
silent denylist between the on-screen ReAct agent and the channel-driven
agent — they share the registry one-for-one.

**Verification.** From Telegram, send: "Set the OpenAI key to … then list my
configured channels then update the global default to OpenRouter". You should
see three tool calls in the session timeline (`apikey_set`, `channel_list`,
`config_update`) and matching state changes in the in-app Settings screen
without restarting the app.

---

## (5) AI creates plugins then runs them via "plugin_execute" instead of registering as real tools

**Root cause.** The model only knew about plugins through the `plugin_execute`
schema entry (which takes `tool` + `text` args), so even though
`PluginManager.resolveTool` *and* `ToolRegistry.runTool`'s dispatcher already
support invoking plugin tools by name, the model defaulted to wrapping every
call in `plugin_execute` because that's what the schema advertised.

**Fix applied (`ToolRegistry.kt`).**
- Removed the `plugin_execute` entry from `listAllTools`'s schema. The
  dispatcher case for `"plugin_execute"` is intentionally kept, so any old
  conversation history or cached prompt that still emits the legacy call
  keeps working.
- Updated `pluginInstall`'s success message to enumerate the registered tool
  names from the manifest and explicitly tell the model: *"Call them DIRECTLY
  by name — do NOT wrap them in plugin_execute. They will appear in the next
  tool schema refresh."*
- Plugin tools were already added to the schema as first-class entries by
  `listAllTools` (`tools.addAll(pluginManager.listAllTools().map { (_, t) ->
  pluginToolToSchema(t) })` was wired pre-fix), and the dispatcher already
  resolves them through `pluginManager.resolveTool(name)`. Removing the
  `plugin_execute` schema entry is sufficient because the agent now *only
  sees* the real tool names in its options.

**Verification.** Ask the in-app agent to "write a plugin that exposes a tool
called `weather_lookup` that takes a city name and returns hard-coded JSON".
After it calls `plugin_install`, the next user message ("look up Paris weather")
should result in a direct `weather_lookup` tool call in the session timeline
— *not* a `plugin_execute` wrapper. Crash-test that old behaviour still
works: paste a manual `plugin_execute` call into the agent's Code Run tool
input and confirm it dispatches successfully (legacy path).

---

## (6) Git tools throw "no virtual method readNBytes" (JGit 6.7 needs API 33+)

**Root cause.** `app/build.gradle` pinned `org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r`,
which calls `java.io.InputStream.readNBytes(int)`. That overload was added in
**API 33**. Forge OS targets `minSdk = 26`, so on every device below Android
13 the first git operation throws `java.lang.NoSuchMethodError: No virtual
method readNBytes`.

A second instance of the same class of bug existed in
`presentation/screens/FileViewerViewModel.kt`, which used `readNBytes(512)` on
arbitrary input streams (file viewer preview).

**Fix applied.**
- **`app/build.gradle`** — downgraded JGit to `5.13.3.202401111512-r`, the
  last release in the 5.x line. 5.x doesn't use `readNBytes` and is API 26
  compatible. (6.0+ requires Java 11; the JGit team made this an explicit
  break.)
- **`FileViewerViewModel.kt`** — replaced the raw `readNBytes(512)` call
  with a manual loop:
  ```kotlin
  val buf = ByteArray(512)
  var read = 0
  while (read < buf.size) {
      val n = stream.read(buf, read, buf.size - read)
      if (n <= 0) break
      read += n
  }
  ```
  Same semantics, no API-33 dependency.

**Verification.** Trigger any git tool (`git_clone`, `git_status`, etc.) on
an Android 12 (API 31) emulator. Operation should complete without
`NoSuchMethodError`. Open a binary file in the file viewer — preview should
render the first 512 bytes as a hex dump, no crash.

---

## Things deliberately *not* changed

- **`PluginManager`** — the plugin tool index already did the right thing;
  no schema or dispatcher rewrite required, only the message + schema
  pruning in fix (5).
- **`ChannelSessionModelOverrides`** — already persisted across process
  death; reused as-is by the new picker.
- **`SecureKeyStore.ApiKeyProvider`** — already exposes `displayName` and
  `defaultModel`, so the picker UI uses real provider metadata instead of
  hard-coding labels.
- **`ProviderRouter`** — the existing auto-pick + fallback chain is
  exactly what we want for the "default route" branch in fix (2); we
  delegate to it by passing `spec = null` to `ReActAgent.run`.

---

## Suggested follow-up (not in this patch)

1. **Per-channel + per-session token-budget cap.** Same plumbing as the
   model override but for `max_tokens`; would let the user keep a Telegram
   bot on `gpt-4o` without paying for 8k-token replies on every casual
   message.
2. **WebView preflight check on app start.** If `WebView.getCurrentWebViewPackage()`
   returns null, surface the same panel as fix (1) inside a banner on the
   home screen so the user knows the in-app browser is unusable *before*
   they tap it.
3. **Plugin tool changelog event in the session timeline.** When
   `plugin_install` registers new tools, append an `Info` event so the user
   can see "registered: weather_lookup, weather_forecast" right there, not
   only in the Plugins screen.
4. **Replace JGit 5.x with `git` CLI shelling out via Termux integration.**
   JGit 5 is on long-term maintenance only; if Forge OS ever raises minSdk
   to 33+, switch to JGit 6.x. Until then, 5.13.x is the right answer.
