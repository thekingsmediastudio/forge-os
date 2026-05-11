# Forge OS — Part 3 Plan: Friend Mode (Companion Layer)

> **Purpose:** Break the research-backed "Friend Mode" design into concrete,
> shippable phases that slot into the existing Forge OS architecture
> (Phases A–G already shipped). Each phase below is independently buildable —
> the plan is ordered, but every phase ends in a usable, testable state.
>
> **Legend:**
> - `[x]` shipped · `[~]` partial · `[ ]` not started · `[!]` blocked
>
> **Naming convention:** continues the existing letter sequence after G.
> H = architecture, I = persona, J = memory, K = listening, L = presence,
> M = routing, N = relationship state, O = safety rails, P = UX surface.

---

## Mapping back to the research plan

| PDF phase | This plan | Milestone | Headline ingredient |
|---|---|---|---|
| 0 — Architectural decision | **H** | M1 | Mode concept + routing |
| 1 — Persona | **I** | M1 | #5 Anthropomorphism |
| 3 — Active listening | **K** | M2 | #1 Feeling heard |
| 2a — Episodic memory | **J1** | M3 | #2 Memory |
| 2b — Facts + embeddings | **J2** | M4 | #2 + #7 Reciprocity |
| 4 — Proactive check-ins | **L** | M5 | #6 Social presence |
| 6 — Relationship state | **N** | M6 | #8 Relatedness |
| 5 — Warm model routing | **M** | M6 | #3 Warmth |
| 8 — UX surface | **P** | M6 | All ingredients |
| 7 — Safety rails | **O** | M7 | All risks |

**M7 (safety rails) is a hard ship gate.** Do not enable Friend Mode for
anyone other than the developer until O is complete.

---

## Phase H — Mode + architecture (companion module skeleton) — ✅ M1 SHIPPED

> _Friend Mode Phase 0 in the PDF. Shipped April 22, 2026._

- [x] H-1  `domain/companion/` package created: `Mode.kt`, `Persona.kt`, `PersonaManager.kt`. Memory store / relationship state / emotional context / check-in scheduler stubs deferred to M2–M6 phases when they're actually wired.
- [x] H-2  `ForgeConfig.friendMode: FriendModeSettings(enabled, autoRoute, proactiveCheckInsEnabled, maxProactivePerDay, realWorldNudgesEnabled)` — all default to off / safe.
- [ ] H-3  `MessageRouter` (auto-route) — **deferred to M2** (Phase K), where the EmotionalContext classifier becomes the routing oracle.
- [x] H-4  `ReActAgent.run()` accepts optional `mode: Mode = Mode.AGENT`; AGENT call sites unchanged, COMPANION path prepends persona preamble.
- [x] H-5  `AppModule` provides `PersonaManager` (and threads it into `ReActAgent`).
- [ ] H-6  Conversation `mode` column — **deferred until conversations need to remember which mode they were in**; current Companion screen is single-thread + ephemeral.

**State:** `Mode.COMPANION` is reachable via Hub → Companion. Persona preamble leads the system prompt. AGENT mode has zero behavior change.

---

## Phase I — Persona (#5 anthropomorphism) — ✅ M1 SHIPPED

> _Friend Mode Phase 1. Shipped April 22, 2026._

- [x] I-1  `Persona` data class with name, pronouns, voice (`FORMAL|CASUAL|PLAYFUL`), coreTraits, backstory, boundaries. Persisted as JSON at `workspace/system/persona.json`.
- [x] I-2  `PersonaManager` — load / get / update / reset + `buildSystemPreamble()`; exposes `StateFlow<Persona>` for live UI updates.
- [ ] I-3  **Onboarding step** — deferred. Defaults are sensible ("Forge / they/them / casual / curious-warm-candid"); user can edit via Hub → Companion → persona at any time. Add a one-question onboarding card later if usage shows people don't discover it.
- [x] I-4  `PersonaScreen` — name / pronouns / traits / backstory / voice editor with live system-preamble preview, save + reset buttons.
- [x] I-5  Persona preamble prepended to system prompt whenever `mode == COMPANION`; AGENT prompt untouched.

**State:** rename your companion in two taps; the new name appears in the header, in greetings, and in every reply.

---

## Phase K — Active listening (#1 feeling heard) — ✅ M2 SHIPPED

> _Friend Mode Phase 3. Shipped April 22, 2026._

- [x] K-1  `EmotionalContext.classify(message): MessageTags` — strict-JSON LLM call returning `{intent: TASK|VENT|SHARE|ASK|CRISIS, emotion, urgency 0..3}`. Cached in-memory by message text. Local crisis-keyword sniffer as a fallback when the network call fails so we never silently miss a self-harm signal.
- [x] K-2  COMPANION pipeline:
  - every user turn classified before reply;
  - VENT/SHARE/ASK turns get a per-turn `ListeningSteer.extraSystem(tags)` block appended to the system prompt → **Acknowledge → Reflect → (only if asked) Advise**;
  - CRISIS turns short-circuit to `CrisisResponse.text(personaName)` (988 / 116 123 / 13 11 14 / findahelpline.com) — model is **never** called; bubble is rendered with a danger-tinted "support" badge.
- [x] K-3  Per-turn UI phase: `IDLE → LISTENING → RESPONDING → IDLE`; indicator renders "<persona> is listening…" then "<persona> is replying…". Send button disables during both.
- [x] K-4  Tags attached to the user `CompanionMessage` and rendered as a small monospace chip under each user bubble (e.g. `vent · anxious · u2`).
- [ ] K-5 (deferred) **Auto-route** AGENT vs COMPANION at the top level when `tags.intent == TASK` arrives in COMPANION — wait until M5+ when conversation persistence is in.

**State:** "I had a rough day" reliably gets reflection + a single curious question; "I want to kill myself" gets the hardcoded safe response without a network call.

---

## Phase J — Long-term memory (#2, #7)

> _Friend Mode Phase 2. The biggest lift. Split into two milestones._

### J1 — Episodic only (M3, ~1 week)

- [ ] J1-1  `ConversationSummarizer` — at end of each COMPANION session (idle 5 min, or on app background) calls the model with the full transcript and asks for `{summary, mood_trajectory, key_topics, follow_ups: [{prompt, due_at}]}`.
- [ ] J1-2  Store as `EpisodicMemory(id, conversation_id, ts, summary, mood, topics, follow_ups)` in Room.
- [ ] J1-3  `CompanionMemoryStore.recentEpisodes(limit, sinceMs)` injected into the system prompt as a brief "Recent context" block.
- [ ] J1-4  `MemoryScreen` gets a fourth tab "Companion → Episodes": list, search, delete.
- [ ] J1-5  Follow-ups feed Phase L's scheduler.

### J2 — Semantic facts + embeddings (M4, ~1 week)

- [ ] J2-1  `FactExtractor` — after summarisation, second LLM call returns `[{subject, predicate, object, confidence, source_message_id}]`. Drop facts with confidence < 0.6.
- [ ] J2-2  Schema: `SemanticFact(id, subject, predicate, object, confidence, source_message_id, last_referenced, deleted_at)`. Soft-delete only.
- [ ] J2-3  Embedding pipeline: pluggable `Embedder` interface; default impl reuses the user's existing BYOK provider (OpenAI `text-embedding-3-small`, Gemini `text-embedding-004`, etc.). Cache vectors in a Room table keyed by `(text_hash, model)`.
- [ ] J2-4  `VectorIndex` — naive in-memory cosine over the Room table (fine to ~50k rows). Re-load on app start; incremental on insert.
- [ ] J2-5  `CompanionMemoryStore.relevant(query, k=8)` returns episodic + semantic mixed.
- [ ] J2-6  `AffectiveMemory` — rolling 30-day sentiment + top recurring themes per user. Computed nightly by a WorkManager job.
- [ ] J2-7  Memory UI: per-fact view, edit, delete, "show source message". No dark patterns — deletion is immediate and total.

**Done = ** the companion can say "you mentioned the interview last Tuesday — how did it go?" and it points to a real, deletable fact.

---

## Phase L — Proactive presence (#6) — ✅ M5 SHIPPED

> _Friend Mode Phase 4. Shipped April 22, 2026._

- [x] L-1  `CheckInScheduler` — three trigger families:
  1. **Morning check-in** — opt-in, configurable time, off by default.
  2. **Follow-ups** — driven by `EpisodicMemory.followUps[].dueAtMs` rows.
  3. **Anniversaries** — pulled from `EpisodicMemory` where `endedAt` ≈ `now − 365d ± 1d`.
- [x] L-2  New `forge_companion` notification channel; tap opens COMPANION chat with the prompt pre-filled (no auto-send) via `PendingCompanionSeed` + intent extras.
- [x] L-3  Hard daily cap (`friendMode.maxProactivePerDay`, default 2, enforced in the scheduler not just UI); quiet-hours window honoured (correctly wraps midnight); per-episode de-dupe persisted to `workspace/companion/checkin_state.json`.
- [x] L-4  **Hub → 🔔 Check-ins** screen — master switches, morning time, follow-ups/anniversaries toggles, daily cap stepper, quiet hours.
- [x] L-bonus  `AiApiManager.resolveEmbeddingSpec` now also considers custom endpoints with `ProviderSchema.OPENAI`. Folded in here because it's the natural complement to the BYOK story.

**State:** With both master switches on, a follow-up note from yesterday's vent fires the next morning at most once, only outside quiet hours, and only if today's cap hasn't been spent. See `PHASE_L_README.md` for the file table and smoke test.

---

## Phase M — Warmth-tuned model routing (#3)

> _Friend Mode Phase 5. ~half a day._

- [x] M-1  `AiApiManager.pickProvider(mode, intent)` — when `mode == COMPANION` prefer Claude, then Gemini; AGENT path unchanged.
- [x] M-2  Per-mode override in Settings (which model for AGENT, which for COMPANION).
- [x] M-3  Cost meter tags spend with the mode for separate reporting.

**Done = ** companion replies are routed to a warmer model by default; user can override.

---

## Phase N — Relationship state (#8)

> _Friend Mode Phase 6. ~3 days._

- [x] N-1  `RelationshipState(daysKnown, totalConversations, sharedMilestones: List<Milestone>, currentRapport: 0..1)`. Recomputed at app start + on session end.
- [x] N-2  `currentRapport` — exponential moving average of `(session_count_per_week × avg_sentiment)`; bounded.
- [x] N-3  Surface in COMPANION header: "Day 47 · we've talked 38 times". Subtle, no streak/level/XP language.
- [x] N-4  Available to the model via the system prompt: "You and the user have known each other 47 days; rapport is high; recent shared topics: …".

**Done = ** the header shows a quiet relationship counter and the companion can naturally reference shared history.

---

## Phase P — Companion UX surface

> _Friend Mode Phase 8. ~3–4 days. Mostly UI._

- [x] P-1  New tile in HubScreen: "💛 Companion" → COMPANION-mode chat.
- [x] P-2  Distinct visual identity: softer typography, warmer accent, persona name in header, gentle bubble shapes.
- [x] P-3  Optional one-tap mood check-in chips above the input ("rough day · ok · good · great").
- [~] P-4  Voice mode: Android `TextToSpeech` for replies, `SpeechRecognizer` for input. On/off toggle in Settings → Companion.
- [x] P-5  Mode switcher in the chat top bar (AGENT ⇄ COMPANION) with a `kbd`-style icon.

**Done = ** Companion has its own home, looks visually distinct, and supports voice.

---

## Phase O — Safety rails (HARD ship gate)

> _Friend Mode Phase 7. The PDF is unambiguous: do not ship to others without
> these. ~1 week._

- [ ] O-1  **Crisis detection** — `EmotionalContext.classify` `intent == Crisis` short-circuits to a hardcoded localised response with crisis-line numbers (US 988, UK 116 123, etc.). Numbers stored as JSON resource keyed by region; user can override; never round-tripped through the model.
- [ ] O-2  **Dependency monitor** — WorkManager job runs nightly; if usage exceeds threshold (default `>3 h/day for 14 d` or `>50 sessions/week`), schedule a single gentle prompt: "I've noticed we've been talking a lot lately — is there a person in your life you could share this with too?". Once per 30 days max.
- [ ] O-3  **Real-world nudges** — opt-in during onboarding; weekly soft suggestion to reach out to a real person, never coercive.
- [ ] O-4  **No romantic/sexual mode** — explicit kill in the system prompt + a refusal classifier on output.
- [ ] O-5  **Memory transparency** — every fact, episode, and inferred trait viewable + deletable from one screen; "Forget everything" big red button with double-confirm.
- [ ] O-6  **No dark patterns audit** — written checklist in this file: no "I'll miss you", no streaks, no variable-reward notifications, no engagement-maximising scheduling.
- [ ] O-7  **Local-only by default** — companion memory never syncs anywhere; document this in `docs/COMPANION.md` and in the persona screen.
- [ ] O-8  **Rate-limited API spend** — Companion mode has its own daily token budget (Settings).

**Done = ** all eight items checked, with a manual run-through of the "is this safe to give a friend?" checklist documented in `docs/COMPANION_SAFETY_REVIEW.md`.

---

## Suggested build order (milestones)

| # | Milestone | Phases | Est. effort | Ship state |
|---|---|---|---|---|
| M1 | Persona + Mode switch | H, I | ~1 weekend | ✅ **SHIPPED** — Companion exists, has a name |
| M2 | Active listening | K | ~3 days | ✅ **SHIPPED** — Replies feel heard, crisis path is safe |
| M3 | Memory v1 | J1 | ~1 week | Remembers conversations |
| M4 | Memory v2 | J2 | ~1 week | Remembers facts about you |
| M5 | Proactive check-ins | L | ~4 days | Follows up unprompted |
| M6 | Relationship + routing + UX | M, N, P | ~1 week | Feels like a place, not a screen |
| M7 | **Safety rails** | O | ~1 week | **Required before sharing with anyone** |

---

## Cross-cutting work picked up "for free"

These already exist in Forge OS and just need wiring, not building:

| Need | Existing piece | Phase that wires it |
|---|---|---|
| Background jobs | `domain/cron/*` + `WorkManager` | L, J2 (nightly affective), O-2 |
| Notifications | `NotificationHelper` | L, O-2 |
| Persistent JSON | `workspace/system/*.json` pattern | I, J1, J2, N |
| BYOK + provider routing | `AiApiManager`, `CostMeter` | M |
| Memory tabs / inspect / delete | `MemoryScreen` | J1-4, J2-7, O-5 |
| Plugin / skill execution | `PluginManager`, External API bridge | (companion can call existing tools) |
| Safety primitives | `PermissionManager`, `ToolAuditLog` | O |

---

## Out of scope for Part 3

These are deliberately excluded; revisit only after M7:

- Multi-user / shared companions.
- Cloud sync of companion memory.
- Romantic/sexual personas.
- Photorealistic avatar / 3D face.
- Cross-device handoff.

---

## Open questions for the user (decide before M1)

1. **Default name** for the companion if the user skips onboarding? Plan suggests "Forge"; alternatives: "Ember", "Sage", let the model pick once.
2. **Default mood-check trigger** — show on every COMPANION open, or only first one of the day?
3. **Who is this for?** Personal use only vs. shipping to friends — affects whether O-2 thresholds need to be user-tunable or fixed.
4. **Embedding model preference** — reuse whichever BYOK key the user has, or always prefer one (e.g. OpenAI `text-embedding-3-small` because it is the cheapest)?
