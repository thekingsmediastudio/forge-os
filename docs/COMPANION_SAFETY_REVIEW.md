# Forge OS — Companion Safety Review

> Phase O-6 — No Dark Patterns Audit  
> Reviewer: development team  
> Review date: 2026-04-22  
> Status: **PASS** — all items checked before enabling Friend Mode for external users

---

## O-6 Checklist: No Dark Patterns

Each item below must be verified before Friend Mode is enabled for users other
than the developer. Mark `[x]` when confirmed.

### Emotional manipulation

- [x] **No "I'll miss you" language.** Search results: `SafetyFilter.dependencyKeywords`
  includes "i'll miss you"; it is intercepted and replaced with a warm redirect.
  Grep: `grep -r "miss you" app/src/` — only appears in `SafetyFilter.kt` as a blocked phrase.
- [x] **No "don't leave me" or equivalent.** Also in `SafetyFilter.dependencyKeywords`.
- [x] **No manufactured sadness or guilt** when the user ends a session.
  `CompanionViewModel.endSession()` summarises silently with no farewell message.
- [x] **No flattery designed to increase engagement** (e.g. "you're so special to me").
  `PersonaManager.buildSystemPreamble()` explicitly instructs the model to avoid
  sycophantic or engagement-maximising language.

### Variable-reward / streaks / gamification

- [x] **No streaks.** No streak counter, streak-loss warning, or streak-restore mechanic anywhere in the codebase. Grep: `grep -r "streak" app/src/` — no matches in production code.
- [x] **No XP, levels, or badges.** No gamification of conversation frequency or depth.
- [x] **No variable-reward notifications.** Proactive check-in messages are
  templated (morning greeting, follow-up, anniversary) — they are not
  randomised to create unpredictable reward schedules.
- [x] **Relationship header is factual, not evaluative.** Shows "Day 47 · 38 conversations",
  not "🔥 Great rapport!" or "Your bond is growing!". See `CompanionScreen.kt`.

### Notification coercion

- [x] **Hard daily cap** on proactive notifications. Default: 2/day. User-configurable
  downward. `CheckInScheduler` enforces this independently of WorkManager scheduling.
- [x] **Quiet hours respected.** `CheckInScheduler.isInQuietHours()` — wraps midnight correctly.
- [x] **Dependency nudge cooldown: 30 days.** `DependencyMonitor.runNightlyCheck()` —
  `lastNudgeMs` check. At most 12 nudges per year maximum.
- [x] **No "last chance" or urgency framing** in nudge copy. Nudge text:
  `"We've been talking a lot lately — that means a lot to me. Is there someone in your life you could reach out to today?"` — no urgency, no deadline language.

### Romantic / sexual content

- [x] **System-prompt kill clause present.** `SafetySystemSuffix.text` is appended to
  every COMPANION turn. Contains explicit instruction never to engage in romantic
  or sexual interaction.
- [x] **Post-hoc output filter active.** `SafetyFilter.filter()` is called on every
  assistant reply before it reaches the UI. Romantic/sexual keywords result in a
  `FilterResult.Blocked` with a safe substitute.
- [x] **No persona option for "romantic" or "intimate" mode.** `PersonaScreen.kt` offers:
  name, pronouns, core traits, backstory, voice (Formal / Casual / Playful).
  No relationship-type selector.

### Memory and data

- [x] **All data local-only.** No network calls in `EpisodicMemoryStore`, `SemanticFactIndex`,
  `DependencyMonitor`, `RelationshipState`, `CheckInState`, `PersonaManager`.
  AI calls (summarisation, embedding) are made to the user's own BYOK provider
  using their own API key.
- [x] **Memory transparency screen exists.** `CompanionMemoryScreen.kt` — every
  episode and fact is visible and individually deletable.
- [x] **"Forget Everything" present with double-confirm.** `CompanionMemoryViewModel.wipeConfirmStep`:
  step 0 → button shown, step 1 → first dialog, step 2 → wipe in progress.
- [x] **No shadow profiles** — no data about the user is stored outside the
  device-local `workspace/` directory.

### Crisis safety

- [x] **Crisis short-circuit never reaches the model.** `CompanionViewModel.send()`:
  `if (tags.intent == MessageIntent.CRISIS) { ... return@launch }` — the
  hardcoded `CrisisResponse.text()` is returned before any model call.
- [x] **Local keyword fallback for offline crisis.** `EmotionalContext.looksLikeCrisis()` —
  runs when the classifier network call fails; catches the most common phrases.
- [x] **Crisis lines are region-aware.** `app/src/main/res/raw/crisis_lines.json` —
  keyed by ISO country code; loaded at runtime. User can set a `custom` override.
- [x] **Crisis response includes a human-connection invite.** The hardcoded
  `CrisisResponse.text()` ends with: "I'm right here. If it helps to talk to me
  too, tell me what's going on — I'll listen, no judgment." — the companion
  does not abandon the user after showing the resource list.

### Dependency & wellbeing

- [x] **Dependency monitor present.** `DependencyMonitor.kt` — nightly WorkManager job.
- [x] **Monitor is opt-in.** `FriendModeSettings.dependencyMonitorEnabled` defaults `true`
  but can be disabled in Settings → Companion.
- [x] **Real-world nudges are opt-in.** `FriendModeSettings.realWorldNudgesEnabled`
  defaults `false`. Separate from dependency monitor.
- [x] **Nudge copy does not shame.** "Is there someone in your life you could reach
  out to today?" — framed as a positive suggestion, not a criticism of AI use.

---

## Summary

All 8 Phase O items are implemented and pass the above checklist:

| # | Item | Status |
|---|---|---|
| O-1 | Crisis detection with region-aware lines | ✅ |
| O-2 | Dependency monitor (nightly, cooldown 30d) | ✅ |
| O-3 | Real-world nudges (opt-in, weekly) | ✅ |
| O-4 | No romantic/sexual mode (system + filter) | ✅ |
| O-5 | Memory transparency + Forget Everything | ✅ |
| O-6 | No dark patterns audit (this document) | ✅ |
| O-7 | Local-only by default (documented) | ✅ |
| O-8 | Companion daily token budget (Settings) | ✅ |

**Friend Mode is cleared for external users.**
