# Phase L — Proactive Companion Check-Ins

**Shipped:** 2026-04-22 (M12 / Part 3 / M5)
**Stack position:** Friend Mode #6 — *social presence*
**Default state:** **OFF.** Two master switches (`friendMode.enabled` AND
`friendMode.proactiveCheckInsEnabled`) plus a hard daily cap and quiet-hours
window must all line up before a single notification can fire.

---

## What this drop does

Three families of proactive check-ins, all gated by the same safety pipeline:

1. **Morning check-in** — opt-in. Once per local day, within ±15 min of
   `friendMode.morningCheckInTime` (default `08:30`).
2. **Follow-ups** — re-surfaces a `FollowUp` row from a past `EpisodicMemory`
   whose `dueAtMs` has passed. Each episode is fired at most once.
3. **Anniversaries** — when `endedAt` of a past episode lands within ±1 day
   of `now − 365d`, surfaces "a year ago today we talked about…". Each
   episode is fired at most once.

Tapping a check-in notification opens the Companion screen with the seed
prompt **pre-filled in the input field** so the user can edit before sending.
We deliberately *do not* auto-send.

A new **Settings → Hub → 🔔 Check-ins** screen exposes every knob
(master switches, morning time, follow-ups/anniversaries toggles, daily cap,
quiet hours).

---

## Bonus fix folded into this drop

`AiApiManager.resolveEmbeddingSpec` now also considers **custom endpoints**
(any `ProviderSchema.OPENAI` endpoint with a stored key) when picking an
embedding provider. Previously only the eight built-in providers were
considered, so users running an OpenAI-compatible local server (LM Studio,
Ollama, vLLM, llama.cpp) had to fall back to the local hashed-trigram space
even though their endpoint *could* speak `/embeddings`.

Resolution order is now:
1. The built-in provider named in `routingRules["memory_embedding"]`.
2. A custom endpoint whose **id or name** matches that string.
3. Any other built-in OpenAI-schema provider with a key.
4. Any custom OpenAI-schema endpoint with a key.
5. Local hashed-trigram fallback.

---

## Files added (5)

| File | Role |
|---|---|
| `domain/companion/CheckInState.kt`   | JSON-persisted ledger: per-day count, last morning day, set of fired follow-up + anniversary episode IDs. |
| `domain/companion/CheckInScheduler.kt` | Computes the opportunity list from settings + `EpisodicMemoryStore`, applies the rate limit + quiet-hours filter, and dispatches to `NotificationHelper`. |
| `data/workers/CompanionCheckInWorker.kt` | `HiltWorker` periodic job (15 min minimum). Calls `CheckInScheduler.runOnce()`. |
| `presentation/screens/companion/PendingCompanionSeed.kt` | Process-singleton that hands the seed prompt from `MainActivity` (intent extras) to `CompanionScreen` (LaunchedEffect). |
| `presentation/screens/companion/CompanionCheckInsScreen.kt` | Settings screen + Hilt VM. |

## Files edited (7)

| File | Change |
|---|---|
| `data/api/AiApiManager.kt`                  | `resolveEmbeddingSpec` now considers custom endpoints (steps 2 + 4 above). |
| `domain/config/ForgeConfig.kt`              | `FriendModeSettings` gains `morningCheckInEnabled`, `morningCheckInTime`, `quietHoursStart`, `quietHoursEnd`, `followUpsEnabled`, `anniversariesEnabled`. |
| `domain/notifications/NotificationHelper.kt` | New `forge_companion` channel + `notifyCompanionCheckIn(title, body, seedPrompt, tag)`. PendingIntent carries `nav=companion` and `companionSeed=<prompt>`. |
| `di/AppModule.kt`                           | Provides `CheckInState` + `CheckInScheduler`. |
| `ForgeApplication.kt`                       | Schedules `CompanionCheckInWorker` only when both master switches are on; cancels it otherwise. |
| `presentation/MainActivity.kt`              | Reads `nav` + `companionSeed` extras (in `onCreate` and `onNewIntent`); seeds `PendingCompanionSeed`; routes the new `companionCheckIns` destination; can deep-launch into `companion`. |
| `presentation/screens/companion/CompanionScreen.kt` | `LaunchedEffect` consumes `PendingCompanionSeed` once and pre-fills the input. |
| `presentation/screens/hub/HubScreen.kt`     | New 🔔 "Check-ins" tile under Companion. |

---

## Safety design (Phase O preview)

These rails are intentionally hard-coded, not just defaults:

- **Two master switches required.** `friendMode.enabled` is the global
  Friend Mode flag; `proactiveCheckInsEnabled` is the additional opt-in for
  *outbound* notifications. Toggling either off in Settings cancels the
  worker on next app start (and the worker itself returns early on every
  tick if the flags are off).
- **Daily cap is enforced in the scheduler**, not just the UI. Even if a
  user manually edits the JSON to a higher number, the cap is hard-bounded
  at 6 in the settings screen.
- **Quiet hours wrap midnight correctly** (`22:00 → 07:30` is honored).
- **Episode-level de-dupe** is persisted in `checkin_state.json` so a
  notification cannot re-fire after a worker restart, app re-install (no —
  re-install wipes the file, that's fine), or device reboot.
- **No auto-send.** The notification opens the Companion screen with the
  prompt staged in the input box. The user has to read it and tap send.
- **No streaks, no "I've missed you", no engagement-maximising scheduling.**
  Per the Phase O checklist.

---

## Smoke test

1. Open Hub → 💛 Companion → have a short chat. Wait 5 min (or back out) so
   `ConversationSummarizer` produces an `EpisodicMemory` with a follow-up.
2. Hub → 🔔 Check-ins → toggle **Friend mode enabled** + **Proactive
   check-ins** on. Set morning time to ~2 min from now.
3. Restart the app (so `ForgeApplication` schedules the worker).
4. Within ~15 min, a `forge_companion` notification should appear with the
   morning greeting. Tap it → Companion opens with "Good morning. " in
   the input.
5. Manually set a follow-up's `dueAtMs` to `< now` in
   `workspace/companion/episodes/<id>.json`, restart, wait ≤ 15 min →
   the follow-up notification fires once and the episode id is recorded
   in `workspace/companion/checkin_state.json` so it doesn't re-fire.
6. Toggle proactive check-ins off → next worker tick logs
   `"skipped — friend-mode disabled"` and nothing fires.

---

## Trade-offs / known gaps

- **Worker grain is 15 min** (WorkManager's minimum). The morning check-in
  therefore lands within ±15 min of the configured time, not on the dot.
  Documented in the settings screen sub-text.
- **No exact-alarm path.** That would require `SCHEDULE_EXACT_ALARM` on
  Android 12+ which is a privacy red flag; not worth it for "morning".
- **Anniversaries trigger only at 365 days.** Other natural intervals
  (30, 90, 180) are out of scope here; if any prove valuable add them as
  `anniversaryDaysOptions: List<Int>` in `FriendModeSettings`.
- **No localisation of the seed prompts** yet. They use the persona's
  configured name but the surrounding text is English. Wire into the
  existing language plumbing in Phase P.
- **The settings screen does not validate that morning time is *outside*
  quiet hours.** If a user sets morning to `02:00` with quiet hours
  `22:00–07:30`, no notification will fire. This is correct behaviour —
  quiet hours always win — but the screen could surface a hint. Deferred.
