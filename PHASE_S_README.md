# Phase S — Routing, Padlocks, Git, Downloads

Phase S finishes the "agent that builds and ships things" story:

| Item | What it gives the user                                              | Status      |
|------|---------------------------------------------------------------------|-------------|
| S1   | Real `git_*` tools (init / add / commit / log / diff / branch /     | **shipped** |
|      | checkout / remote\_set / clone / push / pull / status) backed by    |             |
|      | JGit. PATs come from `memory_store git_credentials/<host>` or the   |             |
|      | explicit `token` arg. HTTPS only.                                   |             |
| S4   | Settings → **Model routing** screen: edit the fallback chain (drag- | **shipped** |
|      | free up/down arrows), pick the primary model, and toggle whether    |             |
|      | background callers (companion, alarms, cron) are allowed to fall    |             |
|      | back. Persists into `ForgeConfig.modelRouting`.                     |             |
| S5   | Settings → **Advanced overrides** screen ("the padlock the agent    | **shipped** |
|      | can't open"). Lets the human add extra blocked hosts, blocked file  |             |
|      | extensions, and blocked config-key prefixes that the ReAct loop is  |             |
|      | forbidden to remove. Backed by `ForgeConfig.userOverrides`; the     |             |
|      | `lockAgentOut` flag also blocks the agent from rewriting any        |             |
|      | `permissions.*` config key.                                         |             |
| S5   | Release-build keystore wiring (`signingConfigs.release` reads       | **shipped** |
|      | `app/keystore.properties`, falls back to debug keystore if not      |             |
|      | present so `assembleRelease` still succeeds on a fresh checkout).   |             |
|      | This also fixes `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on reinstalls  |             |
|      | by pinning debug builds to the standard debug signature.            |             |
| S6   | `file_download` + `browser_download` tools. Stream URL → workspace  | **shipped** |
|      | (default `downloads/`), enforce blocked-host / blocked-extension    |             |
|      | lists, sniff MIME from the first 16 bytes, return SHA-256 + byte    |             |
|      | count. `browser_download` reuses the WebView cookie jar so it can   |             |
|      | pull files from pages the user is already logged in to.             |             |
| S7   | This file + ReAct rules #15 (downloads) and #16 (git) + the Tools   | **shipped** |
|      | screen descriptions for every new tool.                             |             |

## Where things live

- `data/git/GitRunner.kt` — JGit wrapper, all git surface area.
- `data/net/DownloadManager.kt` + `data/net/MimeSniffer.kt` — download surface.
- `data/browser/HeadlessBrowser.getCookieHeader()` — cookie helper used by `browser_download`.
- `domain/config/ForgeConfig.kt` — `userOverrides`, `permissions.lockAgentOut`,
  `modelRouting.backgroundUsesFallback`.
- `domain/security/PermissionManager.kt` — merges `userOverrides` into File / Network /
  Config perms; `canModifyConfig` denies any `permissions.*` key when `lockAgentOut`.
- `domain/agent/ToolRegistry.kt` — 14 new tool case-branches + `ALL_TOOLS` entries.
- `presentation/screens/settings/ModelRoutingScreen.kt` (+ ViewModel)
- `presentation/screens/tools/AdvancedOverridesScreen.kt` (+ ViewModel)
- `presentation/screens/SettingsScreen.kt` — two new nav rows (Model routing,
  Advanced overrides).
- `presentation/MainActivity.kt` — `composable("modelRouting")` and
  `composable("toolOverrides")` routes.

## Known follow-ups (not in this phase)

- `modelRouting.backgroundUsesFallback` is persisted and surfaced in the UI but
  callers (`AiApiManager.chatWithFallback`) currently always allow fallback —
  the per-caller gate isn't wired yet. Default-true keeps Phase R behaviour.
- `git_*` tools are HTTPS-only. SSH keys / `ssh-agent` aren't supported on
  Android and there is no plan to add them.
- The padlock list does not yet block `tool_*` keys. The `lockAgentOut` flag
  only locks `permissions.*`; if you also want to freeze tool enable/disable,
  add `tool_` to your `extraBlockedConfigPrefixes` list.
