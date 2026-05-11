# Phase D — Module UI screens (D1–D7)

This drop turns every Phase B/C subsystem (tools, plugins, cron, memory, agents, projects, skills) into first-class UI surfaces and adds an "audit & control" loop on top of `ToolRegistry`.

## What's new

| Area | File | Notes |
|---|---|---|
| Hub | `presentation/screens/hub/HubScreen.kt` | Grid launcher with live counts (tools, plugins, cron, memory, agents, projects, skills). |
| Shared chrome | `presentation/screens/common/DomainTheme.kt` | `ForgeOsPalette`, `ModuleScaffold`, `StatusPill` — every Phase D screen uses these so the look stays consistent. |
| Tools | `presentation/screens/tools/{ToolsScreen,ToolsViewModel}.kt` | List + per-tool toggle (writes through `PermissionManager.updateToolPermission`); recent **audit log** panel reads `ToolAuditLog.entries`. |
| Audit hook | `domain/agent/ToolRegistry.kt` | `dispatch()` now records every call (success or failure) into `ToolAuditLog`. `source = "user"` when `toolCallId` starts with `ui_test_`, else `"agent"`. |
| Plugins | `presentation/screens/plugins/{PluginsScreen,PluginsViewModel}.kt` | Enable/disable, uninstall (built-ins protected), inspector for tools + permissions, install via paste **or** picked `.zip` (manifest.json + entrypoint .py). |
| Cron | `presentation/screens/cron/{CronScreen,CronViewModel}.kt` | List jobs, create with PYTHON / TOOL / AGENT_GOAL payloads, run-now, toggle, delete, recent execution history. |
| Memory | `presentation/screens/memory/{MemoryScreen,MemoryViewModel}.kt` | Three tabs (Daily / Facts / Skills), search, edit/delete, **export + import** as plain JSON via Storage Access Framework, wipe-all confirmation. |
| Agents | `presentation/screens/agents/{AgentsScreen,AgentsViewModel}.kt` | Live list of sub-agents with status pills, transcript viewer, cancel button, "spawn" dialog that calls `DelegationManager.spawnAndAwait`. |
| Projects | `presentation/screens/projects/{ProjectsScreen,ProjectsViewModel}.kt` | Create / activate (drives `ProjectScopeManager.active`) / edit scope (tools, memory tags, agent id) / delete. File counts read from `ProjectsRepository.fileCount`. |
| Skills | `presentation/screens/skills/{SkillsScreen,SkillsViewModel}.kt` | Search, edit, run-in-sandbox preview (uses `SandboxManager.executePython`), per-skill JSON export/import. |
| DI | `di/AppModule.kt` | New `@Provides` for `ToolAuditLog`, `ProjectsRepository`, `ProjectScopeManager`; `ToolRegistry` provider now takes `ToolAuditLog`. |
| Nav | `presentation/MainActivity.kt` | 8 new routes: `hub`, `tools`, `plugins`, `cron`, `memory`, `agents`, `projects`, `skills`. |
| Chat header | `presentation/screens/ChatScreen.kt` | New "Modules" button (Apps icon) → `hub`. `onNavigateToHub` is opt-in (default `{}`) so existing call sites compile. |

## Trade-offs (deferred from the original D-plan)

1. **Plugin install — no biometric gate.** Confirmed via in-dialog warning only; `.zip` picker uses `ActivityResultContracts.GetContent`. A KeyguardManager challenge is the obvious next iteration.
2. **Memory export — plaintext JSON, not encrypted `.forge-memory`.** `MemoryArchive` is `kotlinx.serialization` JSON written via SAF. This unlocks backup + round-trip today; encryption can layer on the same archive shape later.
3. **Audit log — file-backed JSONL, not Room.** Mirrors the existing `cron history` / `ApiCallLog` patterns (`workspace/system/tool_audit.jsonl`) with a 300-entry in-memory ring; avoids pulling Room in for a single feature.
4. **Skill recorder is not in this drop.** Skills can be created, edited, run, exported and imported from the UI, but recording from a Chat conversation will land in a follow-up.
5. **Marketplace + suggested skills** explicitly deferred — both depend on a registry that doesn't exist yet.

## Audit hook contract

`ToolRegistry.recordAudit` is called on **every** dispatch path (permission denial, success, exception):

```kotlin
ToolAuditEntry(
  id          = "audit_${startedAt}_<rand>",
  timestamp   = startedAt,
  toolName    = toolName,
  args        = argsJson.take(400),
  success     = !result.isError,
  durationMs  = max(0, now - startedAt),
  outputPreview = result.output.take(200),
  source      = if (toolCallId.startsWith("ui_test_")) "user" else "agent",
)
```

`ToolsScreen` exposes a "Test" button on each tool that dispatches with `toolCallId = "ui_test_<ts>"` so user-driven runs are visible in the audit panel and distinguishable from agent runs.

## How to navigate

`Chat → Apps icon → Hub → tap any module`. Every module screen has a back arrow that pops to Hub; Hub's back returns to Chat.
