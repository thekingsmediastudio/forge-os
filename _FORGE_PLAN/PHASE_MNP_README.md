# M13 — Phases M + N + P (warmth-tuned routing, relationship state, companion UX)

This milestone closes out the everyday companion experience prior to the
Phase O safety pass. Three small layers, each opt-in or quietly additive.

## Phase M — Warmth-tuned model routing
- `ModelRoutingConfig` gained a `CompanionRouting` block with a warm-stack
  (`anthropic → gemini → openai → groq`) and an optional model override.
- `AiApiManager.pickProviderForMode(mode)` returns the right provider per
  call; `chat(mode = …)` threads the tag through to `CostMeter.record`.
- `CostMeter.CostSnapshot` now tracks `agentUsd`, `companionUsd`,
  `agentCalls`, `companionCalls`. The Cost Stats screen renders them as a
  separate "Agent / Companion" row pair under TOTALS. Defaults are
  backwards-compatible — older callers keep working as `Mode.AGENT`.

## Phase N — Relationship state
- New `RelationshipState` (singleton, persisted to
  `workspace/companion/relationship.json`) holding `firstSeenAt`,
  `totalConversations`, `currentRapport` (0..1) and `sharedMilestones`.
- `currentRapport` is an EMA over recent episodes (7-day half-life,
  bounded). Recomputed on `ForgeApplication.onCreate` and on every
  session end.
- The companion header reads "Day 47 · we've talked 38 times" — quiet
  factual line, **no** streaks/levels/XP language.
- `CompanionViewModel` prepends a one-line "Relationship context: …"
  sentence to the system prompt suffix so the model can reference
  shared history naturally.

## Phase P — Companion UX
- `FriendModeSettings` gained `voiceEnabled` (default OFF),
  `moodChipsEnabled` (default ON), `showRelationshipHeader` (default ON).
- `CompanionVoice` wraps Android `TextToSpeech`; `CompanionViewModel`
  speaks each assistant reply iff the user opted in. (`SpeechRecognizer`
  mic input is intentionally deferred — needs `RECORD_AUDIO` runtime
  permission and a separate UI flow. P-4 is marked `~` in the plan.)
- One-tap mood chips ("rough day · ok · good · great") render above the
  input on a fresh session and pre-fill the composer.
- The chat top bar gained a 💛 icon for AGENT → COMPANION; the
  companion header gained a ⚡ button for COMPANION → AGENT (it pops
  back to a clean "chat" route).

## Files touched / added
- `domain/companion/RelationshipState.kt` (new)
- `domain/companion/CompanionVoice.kt` (new)
- `domain/config/ForgeConfig.kt` — `FriendModeSettings` extended
- `data/api/CostMeter.kt` — mode-tagged spend
- `domain/agent/ReActAgent.kt` — passes `mode` to `chat`
- `presentation/screens/companion/CompanionViewModel.kt` — DI + endSession
  hook + system-prompt suffix + voice playback
- `presentation/screens/companion/CompanionScreen.kt` — relationship line,
  mood chips, AGENT switch button
- `presentation/screens/ChatScreen.kt` — COMPANION switch button
- `presentation/screens/cost/CostStatsScreen.kt` — Agent / Companion rows
- `presentation/MainActivity.kt` — wires the two switch routes
- `di/AppModule.kt` — providers for the two new singletons
- `ForgeApplication.kt` — `recomputeFromEpisodes()` on startup

## Next
- **M14 / Phase O** — HARD ship gate (crisis policy + safety rails).
  Voice mic input (P-4 finish) folds in here once the permission flow
  exists.
