# Phase F — Snapshots, MCP, Conversations, Cost UI

Bundles four features into one drop:

## F1 — Workspace snapshots
- `domain/snapshots/SnapshotManager.kt` — zips the entire `workspace/` (excluding `.snapshots/` and `system/cache/`) into `workspace/.snapshots/<id>.zip` with a sidecar `<id>.meta.json`. Restore wipes the workspace and unzips back. Path-traversal guarded.
- New tools (auto-enabled in default config): `snapshot_create`, `snapshot_list`, `snapshot_restore`, `snapshot_delete`.
- New screen: **Snapshots** (Hub → 📦). Create with optional label, restore with confirmation, delete.

## F5 — MCP (Model Context Protocol) client
- `data/mcp/McpClient.kt` — JSON-RPC 2.0 over HTTP using OkHttp. Supports `tools/list` and `tools/call`. Parses the spec's `content` array (text parts).
- `data/mcp/McpServerRepository.kt` — persists servers to `workspace/system/mcp_servers.json` with name/URL/optional bearer token/enabled flag.
- New tools: `mcp_refresh` (queries every enabled server), `mcp_list_tools` (lists cached tools).
- Discovered tools are surfaced in `ToolRegistry.getDefinitions()` as `mcp.<server>.<tool>` and dispatched directly to the originating server.
- New screen: **MCP** (Hub → 🔌). Add server (name / URL / bearer token), toggle, remove, refresh tool catalog, browse cached tools.

## E3 (completion) — Multi-conversation switcher
- `ConversationRepository` now exposes `currentIdFlow` and `listFlow` so the chat re-hydrates whenever the active conversation changes.
- `ChatViewModel` observes the flow, calls `reloadCurrent()` to swap messages + apiHistory + model selection on switch.
- New screen: **Chats** (Hub → 💬). Lists every conversation with current marker, message count, last update, last model. Open / rename / delete / start new.

## E2 (completion) — Cost stats + price editor
- `CostMeter` now tracks per-model `ModelStats` (calls / tokens / USD), exposes `removePrice` and `resetLifetime`.
- New screen: **Cost** (Hub → 💰). Lifetime / session / last-call totals, per-model breakdown (sorted by spend), full editable price table with add/edit/remove dialogs, reset session/lifetime buttons.

## Files
**New (10):**
- `domain/snapshots/SnapshotManager.kt`
- `data/mcp/McpModels.kt`, `McpClient.kt`, `McpServerRepository.kt`
- `presentation/screens/snapshots/SnapshotsScreen.kt`, `SnapshotsViewModel.kt`
- `presentation/screens/mcp/McpServersScreen.kt`, `McpServersViewModel.kt`
- `presentation/screens/conversations/ConversationsScreen.kt`, `ConversationsViewModel.kt`
- `presentation/screens/cost/CostStatsScreen.kt`, `CostStatsViewModel.kt`
- `PHASE_F_README.md` (this file)

**Modified:**
- `data/api/CostMeter.kt` — per-model stats, `removePrice`, `resetLifetime`
- `data/conversations/ConversationRepository.kt` — `currentIdFlow`, `listFlow`, `rename`
- `domain/agent/ToolRegistry.kt` — MCP + snapshot dispatch & tool defs
- `domain/config/ForgeConfig.kt` — snapshot/mcp tools enabled by default
- `di/AppModule.kt` — providers for `SnapshotManager`, `McpClient`, `McpServerRepository`
- `presentation/MainActivity.kt` — 4 new routes
- `presentation/screens/hub/HubScreen.kt` — 4 new tiles
- `presentation/screens/ChatViewModel.kt` — reacts to `currentIdFlow`, `reloadCurrent()`
- `_FORGE_PLAN/PART2_PLAN.md` — status updates

## Notes / trade-offs
- MCP transport is HTTP only (no stdio, no WebSocket SSE) — suitable for hosted MCP servers; local stdio servers would need a different runner.
- Snapshots use ZIP (no native tar in the Android stdlib) and exclude the snapshots dir to avoid recursion bombs.
- Conversation switch reloads the *current* conversation in the repo; the actual swap happens through the shared `ConversationRepository` flow rather than passing an ID through navigation arguments — keeps `ChatScreen` pure.
- Resetting lifetime cost wipes the per-model breakdown too (one zeroed snapshot).
