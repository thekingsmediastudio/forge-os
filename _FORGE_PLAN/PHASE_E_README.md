# Phase E — Quality & Infra

Bundled additions to the Phase D drop. All Phase D wiring still applies.

## Goals

- E1 — Streaming chat responses + token / USD cost meter.
- E2 — Conversation persistence (the chat reopens to its last session, model
  selection survives a restart).
- E3 — Surface the heartbeat health on every screen of the agent (live dot in
  the chat header, tap to open the Status screen).
- E4 — Skill recorder. Successful Python / shell tool calls are auto-captured
  as skill candidates; the user types `save skill <name>` to commit one to
  the Tier-3 SkillMemory.

## Files added

```
app/src/main/java/com/forge/os/data/api/CostMeter.kt
app/src/main/java/com/forge/os/data/conversations/ConversationRepository.kt
app/src/main/java/com/forge/os/domain/agent/SkillRecorder.kt
_FORGE_PLAN/PHASE_E_README.md   (this file)
```

## Files modified

```
app/src/main/java/com/forge/os/data/api/AiApiManager.kt
app/src/main/java/com/forge/os/di/AppModule.kt
app/src/main/java/com/forge/os/domain/agent/ReActAgent.kt
app/src/main/java/com/forge/os/presentation/screens/ChatViewModel.kt
app/src/main/java/com/forge/os/presentation/screens/ChatScreen.kt
_FORGE_PLAN/CURRENT_STATUS.md
```

## E1 — Streaming + Cost Meter

**CostMeter** is a `@Singleton` that owns a per-model price table (USD per 1M
input/output tokens) and three running totals:

- `lastCallUsd` / `lastInputTokens` / `lastOutputTokens` — most recent call.
- `sessionUsd` / `sessionCalls` — accumulated since process start.
- `lifetimeUsd` / `callCount` — persisted to
  `workspace/system/cost_totals.json`.

Built-in pricing covers the major OpenAI / Anthropic / Groq / Gemini / xAI /
DeepSeek / Mistral models. Unknown models fall back to a conservative default
(`$1 / $3` per 1M). Users can tune any entry at runtime via `setPrice(...)`.

`AiApiManager.callOpenAi` and `callAnthropic` now invoke `costMeter.record(...)`
after every successful call, alongside the existing `ApiCallLog.record(...)`
diagnostic write.

The chat header renders a small `$0.NNN` pill (session spend or last-call
spend, whichever is greater) whenever any call has been made. Type `cost` /
`spending` / `tokens` for a textual breakdown.

**Streaming** — true SSE was deferred (tool-calling deltas are non-trivial to
correctly merge across providers). Instead, `ReActAgent` chunks the final
assistant text into ~3-word groups and emits successive `AgentEvent.Thinking`
events so the UI bubble fills in progressively (~18 ms per group). Existing
intermediate `Thinking` events from tool-call iterations still render
verbatim. This is a UX-only improvement — the underlying provider call is
still non-streaming.

## E2 — Conversation Persistence

**ConversationRepository** stores one JSON file per conversation in
`workspace/conversations/<id>.json` and a `current.txt` pointer to the active
conversation.

Each `StoredConversation` contains:

- `id`, `title`, `createdAt`, `updatedAt`
- `lastProviderLabel` / `lastProviderName` / `lastModel` — used to restore
  the model picker on relaunch (closes Phase A item A1.5).
- `messages: List<StoredChatMessage>` — UI bubbles.
- `apiHistory: List<StoredApiMessage>` — the `(role, content)` pairs sent
  back to the LLM for context.

`ChatViewModel` now:

1. Calls `loadOrCreateCurrent()` in `init` and rehydrates both `_messages`
   and `apiHistory` in place.
2. Reapplies `lastModel` to `_selectedSpec` if the matching provider is still
   available (toggling out of auto-route).
3. Calls `persistCurrent()` after every send / model change / new chat.
4. Adds a `new chat` / `new conversation` local command that calls
   `conversationRepo.newConversation()`, swaps `currentConversation`, and
   resets the in-memory state.

Tool-call bubbles are intentionally **not** roundtripped through `apiHistory`
on restore — the LLM-side conversation is just user / assistant text, which
matches what the original `apiHistory.add(...)` calls did anyway.

## E3 — Heartbeat surfacing

`HeartbeatMonitor.start()` was already invoked from `ForgeApplication.onCreate`,
so the `_status: StateFlow<SystemStatus>` is live from the moment the app
starts.

`ChatViewModel` now exposes `systemStatus = heartbeatMonitor.status` and the
chat header renders a colored ● dot (green/amber/red/grey) right of the
loading indicator. Tapping the dot opens the existing Status screen.

## E4 — Skill recorder

`SkillRecorder` is a small, in-memory observer with one slot for the most
recent candidate.

- `noteUserRequest(text)` is called from `ChatViewModel.send` so the captured
  description carries the user's intent.
- `recordToolUsage(toolName, args, isError)` is called from
  `ChatViewModel`'s `AgentEvent.ToolResult` branch. It only captures
  `python_exec`, `shell_exec`, and `code_run` calls that succeeded, and
  extracts the `code` / `script` / `command` field from the JSON args.
- `commit(name)` writes the captured candidate into `SkillMemory` with tags
  `[<toolName>, "auto-recorded"]`.

The user commits a candidate by typing `save skill <name>`. The Skills
module screen (Phase D) then shows the new entry with its full source.

## Smoke test

1. Launch the app on a real device (Chaquopy + JDK17 + NDK required for the
   APK build).
2. Send any chat message that requires a Python tool call (e.g. `compute
   2**128`). Expect: progressive bubble fill, a `$0.NNN` pill in the header
   after the response lands, and a green ● next to it.
3. Type `cost` — confirm session / lifetime totals look reasonable.
4. Type `save skill big_pow` — confirm "✅ Saved skill `big_pow` …".
5. Open Hub → Skills — confirm the new skill is listed.
6. Force-stop the app, relaunch — chat should reopen on the same
   conversation, with the same model still selected and the previous
   messages visible.
7. Type `new chat` — confirm a fresh greeting and an empty cost pill (the
   pill keeps showing because lifetime > 0; the value is the session total).

## Trade-offs / deferred work

- Streaming is UX-side chunked emit, not real SSE. Tool-call streaming will
  arrive in a follow-up if/when there is demand.
- `apiHistory` persistence drops `tool` / `tool_call` roles — only user /
  assistant turns are restored. Acceptable for context window seeding.
- No conversation switcher UI yet (multi-conversation list view) — the
  underlying repository supports it, only `new chat` is wired.
- CostMeter pricing table is hard-coded at compile time. A "manage prices"
  screen is a Phase F candidate.
- Skill recorder captures only the latest one candidate at a time — no
  history queue. Sufficient for the typical "I just did something useful,
  save it" flow.
