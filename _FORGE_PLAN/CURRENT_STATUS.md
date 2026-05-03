
# Forge OS — Current Status

**Snapshot date:** 2026-04-22 (Phase O — Safety Rails, M14 complete)
**Source zip:** `forge-os-master_1776856880259` + Phase A + diagnostics + Phase B + **Phase C** + **Phase D** overlays + Phase E + Phase F + Phase G + Part 3 M1–M7

## You are here

```
[x] M0  Carry-over fixes        (Python init order)
[x] M1  Phase A — Chat unblock  (model picker, providers, custom endpoints,
                                  errors, retries, diagnostics)
[x] M2  Phase B — Theming       (light/dark/system)
[x] M3  Phase C — Workspace browser
                                  (explorer + breadcrumb + sort + search +
                                   viewer/editor + quick actions + multi-select)
[x] M4  Phase D — Module UI screens
                                  (Hub + Tools/Plugins/Cron/Memory/Agents/
                                   Projects/Skills + ToolRegistry audit hook)
[x] M5  Phase E — Quality & infra (chunked streaming, CostMeter,
                                   conversation persistence, heartbeat pill,
                                   skill recorder)
[x] M6  Phase F — Snapshots / MCP client / cost stats
[x] M7  Phase G — Plugin polish + External API for other apps
[x] M8  Part 3 / M1 — Phases H + I (Friend Mode: persona + mode switch)
[x] M9  Part 3 / M2 — Phase K (active listening + crisis short-circuit)
[x] M10 Part 3 / M3 — Phase J1 (episodic memory)
[x] M11 Part 3 / M4 — Phase J2 (semantic facts + embeddings)
[x] M12 Part 3 / M5 — Phase L (proactive check-ins)
[x] M13 Part 3 / M6 — Phases M + N + P (routing, relationship, UX)
[x] M14 Part 3 / M7 — Phase O (safety rails) ← COMPLETE — SHIP GATE CLEARED
```

**All milestones complete. Friend Mode is cleared for external users.**

See `docs/COMPANION_SAFETY_REVIEW.md` for the full no-dark-patterns audit checklist.

---

## What just landed (M14 — Phase O, safety rails)

### O-1 — Region-aware crisis lines
- **`app/src/main/res/raw/crisis_lines.json`** — JSON resource keyed by
  ISO 3166-1 alpha-2 country code (US, CA, GB, IE, AU, NZ, ZA, IN, DE, FR)
  plus `"default"` (findahelpline.com) and a `"custom"` slot the user can
  fill via `FriendModeSettings.crisisLineCustomText`.
- **`domain/companion/CrisisLines`** (new object in `EmotionalContext.kt`) —
  loads and resolves lines at runtime using `configRepository.friendMode.crisisLineRegion`
  (auto-detected from device locale when empty).
- **`CrisisResponse.text(personaName, lines)`** — overloaded to accept the
  loaded lines; old single-arg overload retained for callers without context.
- **`CompanionViewModel`** — passes region config into `CrisisResponse.text`.

### O-2 — Dependency monitor
- **`domain/companion/DependencyMonitor.kt`** — new singleton. Tracks per-day
  session counts and wall-clock durations in `workspace/companion/dependency_state.json`.
  `runNightlyCheck()` evaluates two thresholds (consecutive-days hours AND
  sessions-per-week) and fires a nudge notification at most once per 30 days.
- **`data/workers/DependencyMonitorWorker.kt`** — `HiltWorker`, 24-hour period,
  battery-not-low constraint. Scheduled by `ForgeApplication.onCreate`.
- **`CompanionViewModel`** — calls `dependencyMonitor.recordSessionEnd(startMs)`
  in `endSession()`.
- **`FriendModeSettings`** — new fields: `dependencyMonitorEnabled` (default `true`),
  `dependencyThresholdHoursPerDay` (default `3.0f`),
  `dependencyThresholdConsecutiveDays` (default `14`),
  `dependencyThresholdSessionsPerWeek` (default `50`).

### O-3 — Real-world nudges
- `FriendModeSettings.realWorldNudgesEnabled` (already present from Phase H)
  is now wired: when `true`, `CheckInScheduler` includes a weekly real-world
  nudge prompt in its rotation. Default: `false` (opt-in).

### O-4 — No romantic/sexual mode
- **`domain/companion/SafetyFilter.kt`** — new singleton. Post-hoc keyword
  scan on every completed assistant reply. Two categories: `romantic_sexual`
  and `dependency_language`. Blocked replies are replaced with warm, safe
  alternatives. All decisions logged at DEBUG.
- **`domain/companion/SafetySystemSuffix`** — system-prompt clause injected
  every COMPANION turn to steer the model away from romantic/sexual content
  before generation.
- **`CompanionViewModel`** — `safetyFilter.filter()` called on every
  `AgentEvent.Response`; `SafetySystemSuffix.text` appended to the per-turn
  system suffix. Blocked messages set `isSafetyBlocked = true` on the bubble.

### O-5 — Memory transparency
- **`presentation/screens/companion/CompanionMemoryScreen.kt`** — new screen.
  Lists all episodes (with timestamp, summary, topics) and all stored long-term
  facts. Every item has an individual Delete button. A prominent "Forget
  Everything" button triggers a two-step confirmation (`wipeConfirmStep`
  0 → 1 → 2) before calling `wipeAll()` on all three stores.
- **`presentation/screens/companion/CompanionMemoryViewModel.kt`** — drives the
  screen; exposes `episodes`, `facts`, `summary`, `wipeConfirmStep`.
- **`HubScreen`** — new "🗑 Companion Memory" tile → `companionMemory` route.
- **`MainActivity`** — new `companionMemory` composable route.
- Phase O-7 local-only notice banner rendered at the top of the screen.

### O-6 — No dark patterns audit
- **`docs/COMPANION_SAFETY_REVIEW.md`** — written checklist; every item
  verified against the live codebase. All items pass.

### O-7 — Local-only by default
- **`docs/COMPANION.md`** — new doc explaining exactly what data is stored,
  where, and how to delete it. Notes on Android backup behaviour.
- Local-only notice banner shown on `CompanionMemoryScreen`.

### O-8 — Companion daily token budget
- **`FriendModeSettings.companionDailyTokenBudget`** — new field, default
  `50_000` tokens. `CompanionViewModel.checkTokenBudget()` evaluates spend
  after each reply; exposes `budgetExhausted: StateFlow<Boolean>` for the UI.

---

## Files added (Phase O, 8 files)
- `app/src/main/res/raw/crisis_lines.json`
- `domain/companion/SafetyFilter.kt`
- `domain/companion/DependencyMonitor.kt`
- `data/workers/DependencyMonitorWorker.kt`
- `presentation/screens/companion/CompanionMemoryScreen.kt`
- `presentation/screens/companion/CompanionMemoryViewModel.kt`
- `docs/COMPANION.md`
- `docs/COMPANION_SAFETY_REVIEW.md`

## Files edited (Phase O, 7 files)
- `domain/companion/EmotionalContext.kt` (CrisisLines object, region-aware CrisisResponse)
- `domain/config/ForgeConfig.kt` (FriendModeSettings: O-2, O-3, O-8 fields)
- `domain/notifications/NotificationHelper.kt` (wellbeing channel + notifyDependencyNudge)
- `di/AppModule.kt` (SafetyFilter + DependencyMonitor providers)
- `ForgeApplication.kt` (DependencyMonitorWorker.schedule)
- `presentation/screens/companion/CompanionViewModel.kt` (O-1/O-2/O-4/O-8 wiring)
- `presentation/screens/hub/HubScreen.kt` (companionMemory tile)
- `presentation/MainActivity.kt` (companionMemory route)
