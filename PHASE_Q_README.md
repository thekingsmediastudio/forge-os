# Phase Q — Control, persistence, notifications, web tools, LAN serve, proactive

This phase adds seven user-requested capability families on top of the existing
14 phases. Everything is wired through Hilt and goes through a single
`AgentControlPlane` so the user (and the agent, when allowed) can see and toggle
every behavior at runtime.

## 1. Toggleable security restrictions w/ explicit consent
- `domain/control/AgentControlPlane.kt` — 19 named capabilities (FS write,
  shell exec, network, plugin install, notifications, web screenshot, browser,
  LAN serve, proactive worker, etc.). Each has a default and an effective
  state, persisted to `workspace/system/control.json`.
- `domain/control/UserConsentLedger.kt` — append-only `workspace/system/consent.log`
  recording every grant/revoke (who/when/why).
- `data/sandbox/SecurityPolicy.kt` was refactored to read its enforcement flags
  through an injected `Flags` interface; `AppModule` connects the plane to the
  policy at startup so flipping a capability live actually changes the sandbox.
- Tools: `control_list`, `control_describe`, `control_set`, `control_grant`,
  `control_revoke`, `control_status`. Tools dispatched with `source="agent"`
  cannot grant themselves new permissions — only `source="user"`/`"ui"` can.

## 2. Plugins survive APK upgrades w/ a stable API
- `PluginManifest.kt` gained `minApiVersion` / `maxApiVersion`.
- `PluginCompatibility.kt` declares `HOST_API_VERSION = 2` and gates loads.
- `PluginExporter.kt` mirrors every installed plugin to
  `getExternalFilesDir("forge_plugins")` as a `.fp` zip — that directory
  survives reinstall. `PluginManager.init()` restores any missing plugin from
  the export dir on first boot. `install`/`uninstall` keep the mirror in sync
  and `reExportAll()` lets the user rebuild the cache.
- Tools: `plugin_export_all`, `plugin_export_list`, `plugin_restore_missing`.

## 3. Agent notifications with clickable actions
- `domain/notifications/AgentNotificationBuilder.kt` — type-safe API for posting
  rich notifications with up to 3 action buttons.
- `NotificationActionRegistry.kt` — central dispatcher; MainActivity can
  override the default to route taps back into the agent runtime.
- `NotificationActionReceiver.kt` (`@AndroidEntryPoint`) — receives
  `com.forge.os.action.AGENT_NOTIF` broadcasts (registered in
  `AndroidManifest.xml`) and forwards to the registry.
- Tool: `notify_send` (title, body, optional actions JSON).

## 4. Hardware/web tools incl. URL screenshot
- `data/web/WebScreenshotter.kt` — offscreen `WebView` that loads any URL,
  waits for `onPageFinished`, then captures a PNG to
  `workspace/screenshots/<slug>-<ts>.png`. Gated by capability `WEB_SCREENSHOT`.
- Tool: `web_screenshot { url, viewport_w?, viewport_h?, wait_ms? }`.

## 5. Browser session w/ visible history
- `data/browser/BrowserHistory.kt` — persistent per-session navigation log at
  `workspace/browser/history.json`. Records `(ts, sessionId, url, title, source)`
  and exposes a `StateFlow` so a dedicated screen can render it.
- `ToolRegistry.browserNavigate()` records every agent navigation.
- Tools: `browser_history_list`, `browser_history_clear`, `browser_session_new`.

## 6. Serve the project on LAN (HTTP) — auto-discoverable
- `data/server/ProjectStaticServer.kt` — embedded static HTTP server that
  serves any `workspace/<path>` over the device's Wi-Fi IP. Multiple roots can
  be served on different ports simultaneously.
- Tools: `project_serve { path, port? }`, `project_unserve { port }`,
  `project_serve_list`. Returns the LAN URL the user can paste into a desktop
  browser (e.g. `http://192.168.1.42:8765/`). Gated by capability `LAN_SERVE`.

## 7. Proactive helpful behavior
- `domain/proactive/ProactiveScheduler.kt` + `ProactiveWorker.kt`
  (`@HiltWorker`, 30-minute periodic) — runs a "what could I do for the user
  right now" pass that consults heartbeat + recent memory and may surface
  suggestions through `AgentNotificationBuilder` (only when the
  `PROACTIVE_BEHAVIOR` capability is enabled).
- Tools: `proactive_status`, `proactive_schedule`, `proactive_cancel`.

## Manifest / app wiring
- `AndroidManifest.xml`: added `ACCESS_WIFI_STATE` permission and registered
  `NotificationActionReceiver`.
- `ForgeApplication.onCreate()`: touches the control plane (loads + logs the
  capability summary), installs a default notification dispatcher, and calls
  `proactiveScheduler.ensureScheduled()`.
- `ForgeConfig.toolRegistry.enabledTools`: all 22 new tool names appended so
  they're discoverable by the agent without flipping `enableAllTools`.

## Defaults
Every restrictive capability defaults to **ON** (i.e. restricted). Users (or
the agent, with `source="user"`) flip them off explicitly via `control_set` —
that flip is recorded in `consent.log` and immediately reflected in
`SecurityPolicy`. Everything is reversible at any time.
