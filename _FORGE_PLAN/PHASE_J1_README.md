# Phase J1 — Episodic Memory

**Milestone:** Part 3 / M3 (M10 in the master roadmap)
**Status:** ✅ shipped

## What it does

After every Companion (Friend Mode) session ends, the conversation transcript is
condensed into a single **EpisodicMemory** record and persisted on-disk. The
last few episodes are then injected into the system prompt at the start of the
next session, so the companion remembers what was talked about and how the
person seemed to be feeling.

This is the *narrative* memory layer (what happened / how it felt). It is
deliberately separate from the existing factual memory layer (`MemoryManager`,
the FACTS / SKILLS / DAILY tabs).

## When sessions end (and get summarised)

Any of:

1. **Idle** — no user message for 5 minutes after the last reply.
2. **Navigation** — the user leaves the Companion screen (`DisposableEffect`).
3. **Lifecycle** — the platform finally clears the ViewModel (`onCleared`).
4. **Explicit** — `vm.endSession()` is called.

A summary is **only** written when the session has at least 2 turns, and an
`alreadySummarised` guard prevents double-writes when several triggers fire
together.

## Where it lives on disk

```
workspace/companion/episodes/
  ep-1729812345678.json
  ep-1729812400000.json
  ...
```

One file per episode, plain JSON, hand-readable, exportable as-is.

## Episode shape

```kotlin
data class EpisodicMemory(
  val id: String,
  val conversationId: String,
  val timestamp: Long,        // when the summary was written
  val startedAt: Long,        // first turn ts
  val endedAt: Long,          // last turn ts
  val summary: String,        // 1–3 sentence narrative
  val moodTrajectory: String, // e.g. "anxious → calmer"
  val keyTopics: List<String>,// 0–6 short tags
  val followUps: List<FollowUp>,
  val tags: List<String>,
)

data class FollowUp(
  val question: String,       // "How did the interview go?"
  val dueAfterMs: Long,       // delay from now until it's worth raising
)
```

`followUps` are stored now and consumed by Phase L (proactive check-ins) later.

## How it gets injected back

`EpisodicMemoryStore.buildContextBlock(limit = 3)` renders the most recent
episodes as a `RECENT CONTEXT` block, e.g.

```
RECENT CONTEXT (last sessions, oldest → newest):
- 2 days ago — They were anxious about a job interview Friday.
  mood: anxious → resolved · topics: job, interview
- yesterday — Talked through a fight with their sister.
  mood: hurt → reflective · topics: family, sister
- 3h ago — Quick check-in, generally fine.
  mood: stable · topics: weekend
```

`CompanionViewModel` concatenates this block with the per-turn
`ListeningSteer.extraSystem(tags)` and passes the result to
`ReActAgent.run(..., extraSystemSuffix = …)`. The block is rebuilt on every
turn so it stays fresh as the user racks up more episodes.

## Summariser

`ConversationSummarizer.summarize(conversationId, turns, recentTags)`:

1. Builds a strict-JSON prompt (schema embedded in the system prompt).
2. Calls `AiApiManager.chat(...)` using the user's auto-routed default
   provider — no extra config, no extra key.
3. Parses the JSON. On parse failure or any model error, falls back to a
   deterministic local summariser that pulls the longest user lines and
   reads the dominant emotion from `recentTags`. The local fallback is
   intentionally crude — it is a safety net, not the happy path.

## UI surface

The **Memory** screen (Hub → Memory) gains a fourth tab: **EPISODES**.

- One card per episode: timestamp, summary, mood trajectory, topics, follow-ups.
- Per-card delete.
- The existing search bar filters episodes by summary / topic / mood.
- "Wipe all memory" now also wipes episodes.
- Episodes are read-only in the UI (you can delete, you can't hand-edit) — they
  are model output. If you want a fact or a skill, use those tabs.

## What's deliberately NOT in this drop

- **No Room migration.** Episodes are JSON files, same convention as the rest
  of the companion package.
- **No embeddings / semantic search.** That's Phase J2 (M11). Episode search is
  substring-only for now.
- **No proactive raising of follow-ups.** That's Phase L (M12). Follow-ups are
  stored on disk but nothing fires them yet.
- **No edit dialog.** Read-only by design — see above.

## Smoke test

1. Open Hub → 💛 Companion.
2. Have a 3-turn conversation (e.g. "I had a rough day at work" → reply →
   "my manager was being unfair" → reply → "thanks for listening" → reply).
3. Press back to leave the screen — this triggers `endSession`.
4. Open Hub → Memory → EPISODES tab. You should see one card with a summary,
   a mood trajectory, and 1–3 topics.
5. Re-open Companion. The opening greeting won't change, but send a new
   message — under the hood the prompt now contains a `RECENT CONTEXT`
   block referencing the previous session. Easiest way to verify behaviour:
   say "do you remember what I told you yesterday?" and watch the reply.
6. Memory → EPISODES → tap **DEL** on a card → it disappears immediately.
7. Memory → menu → **Wipe all memory** → episodes are also gone.

## Files

Added (3):
- `app/src/main/java/com/forge/os/domain/companion/EpisodicMemory.kt`
- `app/src/main/java/com/forge/os/domain/companion/EpisodicMemoryStore.kt`
- `app/src/main/java/com/forge/os/domain/companion/ConversationSummarizer.kt`

Edited (5):
- `app/src/main/java/com/forge/os/presentation/screens/companion/CompanionViewModel.kt`
- `app/src/main/java/com/forge/os/presentation/screens/companion/CompanionScreen.kt`
- `app/src/main/java/com/forge/os/presentation/screens/memory/MemoryViewModel.kt`
- `app/src/main/java/com/forge/os/presentation/screens/memory/MemoryScreen.kt`
- `app/src/main/java/com/forge/os/di/AppModule.kt`
