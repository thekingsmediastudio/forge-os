# Forge OS — Patch Notes (this drop)

## Bugs fixed in source

1. **Model picker crashed mid-scroll with `LazyColumn key already used` (e.g. `m_MISTRAL_voxtral-mini-2507`).**
   Some catalogs (Mistral most reliably) return the same model id more than
   once. The picker keys items by `m_<provider>_<model>`, so duplicates
   exploded the LazyColumn the moment it tried to compose the second copy.
   - `data/api/AiApiManager.kt` — model fetchers now `.distinct().sorted()`
     instead of `.sorted()`. Applies to `fetchOpenAiModels`,
     `fetchAnthropicModels`, and `fetchCustomOpenAiModels`.
   - `presentation/screens/channels/ChannelSessionViewScreen.kt` — defensive
     `models.distinctBy { it.model }` right before the `LazyColumn.item(...)`
     loop so a stale cache or a future provider can never reproduce the crash.

2. **Channel picker only showed Mistral (the first added provider).**
   Same root cause as #1 — the LazyColumn crashed on the first duplicate key,
   so every provider added *after* the duplicate disappeared from the visible
   list and the screen looked like only the first provider was wired up.
   The de-dup fix above restores the full list.

3. **`channel_send` (and `sendByName`) didn't save the outbound message to
   chat history.** When the agent pushed an unsolicited reply via the
   `channel_send` tool, Telegram delivered it but the in-app session view
   stayed empty. `sendVoice` already mirrored the message; the text path
   never did.
   - `domain/channels/ChannelManager.kt` — `send()` now calls a new
     `recordOutgoingText(...)` helper on success, which writes a
     `SessionEvent.Kind.OutgoingText` to `sessionStore`. This is harmless
     when the agent is the one calling it from inside its own chat (the
     auto-reply path is suppressed in that case via
     `suppressAutoReplyFor(routeKey)`, so we don't double-record).

4. **Plugin tools returned `null` / `PLUGIN_ERROR` (e.g. the user's
   `custom_roll_dice`).** Two compounding bugs:
   1. `domain/agent/ToolRegistry.kt#pluginExecute` forwarded the *entire*
      args map — including the dispatch envelope key `"tool"` — into the
      plugin function as kwargs. Python then raised
      `TypeError: roll_dice() got an unexpected keyword argument 'tool'`.
   2. `domain/plugins/PluginManager.kt#executeTool` then ran
      `args.mapValues { it.value?.toString() ?: "" }` before serialising,
      coercing every value to a String. A plugin written as
      `def roll(num_dice: int = 1, num_sides: int = 6)` saw `num_dice="2"`
      and crashed inside `range("2")`.

   Both are fixed:
   - `pluginExecute` strips the `"tool"` key before forwarding.
   - `PluginManager.executeTool` now serialises args with a new
     `jsonifyAny(...)` helper that preserves numbers, booleans, lists and
     nested objects as their proper JSON tokens.

5. **Browser screen crashed on open with
   `java.lang.VerifyError: Verifier rejected class BrowserScreenKt`.**
   The single `BrowserScreen` Composable had grown to hundreds of locals
   across dozens of nested lambdas (preflight + giant `AndroidView` factory
   + WebChromeClient + WebViewClient + JS bridge + lifecycle callbacks).
   On at least one device family ART's bytecode verifier rejected it with
   `copy1 v13<-v281 type=Precise Reference: BrowserViewModel`.
   - `presentation/screens/BrowserScreen.kt` — the entire WebView surface
     is now a separate `BrowserWebPanel(...)` private composable invoked
     from `BrowserScreen`. Behaviour is identical; the bytecode for both
     methods is now well under the verifier's register-merge limits.

6. **Agent system-prompt: "self-test after debugging" rule.** Added rule
   #20 in `domain/agent/ReActAgent.kt#baseSystemPrompt` instructing the
   agent to re-run failing inputs after any fix, surface full error text
   instead of summarising it away, and never declare a fix successful
   without proof. This is the in-prompt counterpart to the user's request
   that Forge always emit clear debugging output.

## Known limitation — NOT fixed in this drop

### Telegram polling stops after ~5 minutes idle

`ForgeApplication.onCreate()` calls `channelManager.startAll()`, which
spins up Telegram long-poll coroutines on a `SupervisorJob`-backed scope
owned by the `ChannelManager` singleton. That scope lives only as long as
the **app process** does. Android (and especially Vivo / OPPO / Xiaomi
OEMs) will kill the process aggressively when the app moves to the
background, which silently kills the polling loop — the bot then appears
"deaf" until the user opens the app again.

This is **not auto-upgradable** by editing the agent prompt or the tool
layer; it requires a real foreground service that holds an ongoing
notification while the channels run. The recommended fix:

1. Add a new `ChannelForegroundService` next to `ForgeHttpService.kt`
   that simply binds to the `ChannelManager` (via Hilt
   `@AndroidEntryPoint`) and posts an ongoing notification.
2. Declare it in `AndroidManifest.xml` with
   `android:foregroundServiceType="dataSync"` (the
   `FOREGROUND_SERVICE_DATA_SYNC` permission is already declared).
3. Start it from `ForgeApplication.onCreate()` immediately after the
   existing `runCatching { channelManager.startAll() }` block, using
   `ContextCompat.startForegroundService(this, Intent(this,
   ChannelForegroundService::class.java))`.
4. Optionally request battery-optimisation exemption on first launch so
   OEM "deep sleep" features stop targeting the process.

This was deliberately left out of the patch because adding a Hilt-injected
service with a manifest entry must be paired with a real device build to
verify the notification renders and the bound channels survive a
`Doze`/`App Standby` cycle. Make that change in a separate, testable PR.

### Auto-upgrade caveat

Forge does not currently ship a self-upgrade pipeline for its own APK —
the agent can edit files inside its workspace, generate plugins, change
config and write skills, but it cannot replace its own `base.apk` from
inside the running process. Real upgrades still require rebuilding the
project and side-loading the new APK. Document this in any user-facing
release notes so the agent doesn't claim the ability when asked.
