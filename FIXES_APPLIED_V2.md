# Forge OS — Fixes Applied (v2)

This patch is layered on top of the previous `forge-os-master_fixed` zip.
It targets two real issues plus the launcher icon refresh.

---

## 1. Notifications were visible but did nothing on tap

There were two distinct paths and **both were broken**.

### 1a. Action buttons attached to agent notifications

`AgentNotificationBuilder.postWithActions(...)` lets the agent attach up to
three action buttons. Each button's PendingIntent fires
`NotificationActionReceiver`, which looks up the action token in
`NotificationActionRegistry` and calls `dispatcher.dispatch(action)`.

**Bug.** The only place that ever called `setDispatcher` was
`ForgeApplication.onCreate`, and it installed a "default no-op dispatcher"
that just wrote a Timber log line:

```kotlin
notificationActions.setDispatcher { action ->
    Timber.i("NotificationAction (default dispatcher): ...")
}
```

The comment promised "real dispatcher is wired by MainActivity once the agent
runtime is alive" — but MainActivity never wired anything. So every action
button tap was silently logged and dropped.

**Fix.** `ForgeApplication` now installs a real dispatcher,
`handleNotificationAction(action)`, that routes by `kind`:

| `kind`         | What it does                                                            |
| -------------- | ----------------------------------------------------------------------- |
| `open_screen`  | Reads `payload.screen` (and optional `payload.seed`) and launches MainActivity with the right `nav` extra. |
| `chat_message` | If a turn is suspended on `request_user_input` for the UI route, hands `payload.text` to `UserInputBroker.submitResponse`. Otherwise opens the chat with the text seeded into the input field. |
| `tool_call`    | Reads `payload.name` + `payload.args` and calls `ToolRegistry.dispatch` on a background coroutine — the tool runs even when no chat turn is active. |

`ToolRegistry` is injected as `dagger.Lazy<ToolRegistry>` so its heavy
dependency graph isn't constructed during `Application.onCreate`.

### 1b. Tapping the notification body itself

`NotificationHelper` posts notifications with `Intent` extras like
`nav=companion`, `nav=external`, and `companionSeed=...`. When the activity
was cold-started, MainActivity correctly used `nav` to pick `startDestination`.
But when the activity was already alive, `onNewIntent` re-fired with the new
extras and **nothing happened** — `consumeIntentExtras` only handled
`companionSeed`, and the navigation extra was thrown away.

**Fix.** MainActivity now keeps a `MutableStateFlow<String?> pendingNavRequest`
and a `LaunchedEffect` that observes it. `consumeIntentExtras` pushes any
known route from `nav` into the flow, the side-effect calls
`navController.navigate(route) { launchSingleTop = true }`, and then clears
the value. Routes are validated against a `KNOWN_NAV_ROUTES` whitelist so a
bogus value can't push an invalid destination onto the back stack. `nav=external`
is now also a valid `startDestination`.

Files touched:

- `app/src/main/java/com/forge/os/ForgeApplication.kt`
- `app/src/main/java/com/forge/os/presentation/MainActivity.kt`

---

## 2. Launcher icon

The previous icon was an XML adaptive icon with a generic vector arrow on
`#0B0F14` and **no PNG mipmaps at all**, which means nothing pre-API-26 ever
saw a real icon.

**Fix.** Generated launcher PNGs from the supplied anvil-and-circuitry
artwork at all five standard densities, plus circular variants for round
icons and 108dp foreground bitmaps for the adaptive icon:

```
mipmap-mdpi/      ic_launcher.png  ic_launcher_round.png  ic_launcher_foreground.png
mipmap-hdpi/      ic_launcher.png  ic_launcher_round.png  ic_launcher_foreground.png
mipmap-xhdpi/     ic_launcher.png  ic_launcher_round.png  ic_launcher_foreground.png
mipmap-xxhdpi/    ic_launcher.png  ic_launcher_round.png  ic_launcher_foreground.png
mipmap-xxxhdpi/   ic_launcher.png  ic_launcher_round.png  ic_launcher_foreground.png
```

`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` now point at
`@mipmap/ic_launcher_foreground` (the bitmap) instead of the old generic
vector. The dark `#0B0F14` background is kept for visual continuity with the
artwork. The unused `drawable/ic_launcher_foreground.xml` is left in place
to avoid churning resource IDs but is no longer referenced by either
adaptive-icon manifest.

---

## What was *not* changed

- No new feature work (Telegram self-onboarding, AI key auto-routing for
  inbound media, in-app capability help) was added in this patch. Those
  capabilities already exist in code; what's missing is UX glue. See the
  README and `domain/channels/`, `domain/cron/`, `domain/agent/ToolRegistry`
  for the existing tools (`channel_send`, `channel_send_voice`,
  `channel_create`, `cron_add`, `git_*`, etc.).
- No build/gradle changes. This patch only touches Kotlin source and `res/`.
