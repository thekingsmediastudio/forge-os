# Forge OS — Memory & History-Based Agent Growth Plan

This document captures *how* Forge accumulates knowledge across days,
sessions, channels, and chats, and what the next concrete steps look
like to make that growth continuous instead of episodic.

## 1. What's already in place

Forge already ships a multi-tier memory system. The pieces are:

| Tier | Where | What it stores | How it's read back |
|------|-------|----------------|--------------------|
| Episodic | `domain/companion/EpisodicMemoryStore.kt` | rolling per-day events (chat turns, tool calls, channel messages) | semantic recall + summary feed |
| Long-term facts | `domain/memory/MemoryManager.kt` (`memory_store`) | user-asserted stable facts ("my name is Sam", "code lives in projects/foo") | `memory_recall`, `semantic_recall_facts` |
| Skill memory | `MemoryManager.storeSkill` (`memory_store_skill`) | reusable Python skills the agent wrote | recalled from skill index, runnable via `python_run` |
| Conversations | `data/conversations/ConversationRepository.kt` | one JSON file per chat under `workspace/conversations/<id>.json` | restored at app launch (`loadOrCreateCurrent()`) |
| Per-chat history (channels) | `domain/channels/ChannelManager.chatHistories` | last 20 turns per Telegram chat | replayed verbatim into the next ReAct turn |

The agent's system prompt also receives a `MEMORY CONTEXT` block on
every turn (`MemoryManager.buildContext`) summarising what's in those
tiers that's relevant to the current question. This context is rebuilt
*per turn*, so newly-stored facts show up on the very next message.

## 2. The "memory loss every day" failure mode

The complaint *"the agent forgets everything overnight"* is not caused
by the long-term tiers (those persist on disk forever). It is caused by:

1. The episodic tier rotates daily and only the **current day** is fed
   into the prompt's `MEMORY CONTEXT`.
2. The conversation list survives, but the active conversation is the
   one whose id is in `workspace/conversations/current.txt`. If that
   file is wiped (e.g. user clears app data, or `clearCurrent()` runs)
   the next launch silently creates a brand-new conversation and the
   old one becomes "invisible" until the user re-opens it from the
   Conversations screen.
3. Long-term facts the agent stored without the user's involvement are
   often keyed with a date suffix (`note_2026_04_25`), so a recall query
   from a different day misses them.

## 3. What was changed in this round

Concrete code changes shipped alongside this document:

* `enableAllTools` defaults to **true** in `ForgeConfig.kt`, so the
  agent's prompt now includes definitions for every Telegram, named
  secret, channel, alarm, doctor, and server tool — they were
  registered but silently filtered out before, which is why the model
  kept saying *"I don't have a tool for that"*.
* The system prompt now carries the **current wall-clock time** and a
  compact **catalog of the tools available this turn** (`ReActAgent.kt`,
  rules 18–19). The agent can no longer claim it doesn't know the date,
  and it has a built-in reminder of its own surface area.
* The on-screen browser ships a **modern Chrome 124 desktop UA** plus
  wide-viewport / zoom settings, matching the headless agent browser.
  Sites that sniff for "old browser" (Slack, Notion, Figma, Google
  login) now render normally.
* Channels have a **per-channel provider/model override** and a
  separate **per-session override** (`ChannelSessionModelOverrides`),
  feeding into `ReActAgent.run(spec=…)` so each Telegram chat can pin
  a different model. Empty values fall back to the global default and
  the existing `fallbackChain`.
* A **splash screen** (`SplashActivity` + `Theme.ForgeOS.Splash` +
  `splash_background.xml`) is now the launcher activity. It shows the
  Forge logo for ~1.4 s, then forwards to `MainActivity`.

## 4. Next steps to get continuous, history-based growth

These items are NOT shipped in this drop — they are the planned
follow-ons that make memory genuinely cumulative.

### 4.1 Cross-day episodic recall
Promote the existing daily episodic file into a rolling 30-day window.
On `MemoryManager.buildContext`, include not just *today* but also a
deduplicated, weighted summary of yesterday + last 7 days. Implementation
sketch:

* Add `EpisodicMemoryStore.summarise(daysBack: Int)` returning a single
  paragraph per day.
* Inject those paragraphs (capped at ~1500 chars total) just below the
  long-term facts in the system prompt.
* Add a daily `cron_add` job that calls a "compaction" prompt: read
  yesterday's events, distil them into 3-5 long-term facts via
  `memory_store`, then mark the day as compacted so it isn't recompacted.

### 4.2 Conversation auto-resume + cross-conversation recall
* On launch, if `current.txt` is missing but `list()` is non-empty,
  reopen the most-recently-updated conversation instead of starting a
  new one.
* Add a `conversation_recall` tool that semantic-searches across all
  stored conversations (not just the open one) and returns matching
  excerpts. Wire it to the same embedding model used by
  `semantic_recall_facts`.

### 4.3 Per-channel "personality" memory
Today every channel shares one memory pool. Add a `channelId` tag on
`MemoryManager.logEvent` and a per-channel filter on `buildContext`, so
the Telegram chat with your boss doesn't see facts you stored from your
private notes session — but the agent still has them when you ask
directly in the in-app chat.

### 4.4 Skill / plugin promotion loop
Whenever the agent writes a Python snippet via `python_run` more than
twice in a 7-day window, prompt the user once: *"want me to save this
as a reusable skill?"* If yes, call `memory_store_skill` with a
description derived from the original goal. Track candidates in a small
sqlite table under `workspace/system/skill_candidates.db`.

### 4.5 Tool-use telemetry → reinforcement
The `SkillRecorder` already notes tool usage. Aggregate weekly into a
"tools you used most / tools that errored most" report and surface it
both to the user (Diagnostics screen) and to the agent (top of the
system prompt as an extra one-liner). Over time this gives the agent a
sense of which capabilities are *actually load-bearing* in this user's
workflow vs which are dead weight.

### 4.6 Memory health checks
Extend `doctor_check` with three new probes:

* `memory_recall_smoke` — store a sentinel fact, recall it, delete it.
* `episodic_rotation_age` — alert if the episodic file hasn't rotated
  in >36h (suggests the daily WorkManager job has stalled).
* `conversation_index_stale` — flag conversations where the on-disk
  message count exceeds the in-memory count (suggests a write failure).

## 5. How to verify this round's changes

After installing the rebuilt APK:

1. Open Settings → Tools and confirm the agent reports >120 enabled
   tools (was ~75 before).
2. Ask the chat *"what time is it right now?"* — the agent should reply
   with the actual wall-clock time, not "I don't have access to time".
3. Ask *"do you have any Telegram tools?"* — the agent should list
   `telegram_main_chat`, `telegram_allow_chat`, `telegram_send_voice`,
   etc.
4. Open the on-screen Browser and visit `whatismybrowser.com`. The page
   should report Chrome 124 on Linux, and no "outdated browser" banner.
5. Cold-start the app; the orange Forge splash logo should appear for
   ~1.4 s before the chat UI loads.
6. In the Channels screen, register a Telegram bot and set its model to
   e.g. `OPENAI` / `gpt-4o-mini`. Send a message — the agent's reply
   should come from that model, not the global default.
