# Phase G — Plugin ZIP Install Polish + External App API

> **Status:** DELIVERED. See `docs/EXTERNAL_API.md` for the public surface.
> Highlights: AIDL service + provider + intent activity, ExternalApiBridge as
> chokepoint, per-caller grants & 30 cpm / 50 000 tokens-per-day rate limits,
> off by default, unsigned plugins warn-only, `.fp` = ZIP with manifest +
> entrypoint, plugin storage cap + `.bak/` rollback.

---


Two features bundled:
1. **G1** — Polish the existing `.zip` plugin install flow (no marketplace yet).
2. **G2** — Expose Forge OS to **other apps on the same phone** so any app the user installs can call Forge OS tools, ask the agent something, run a skill, read/write a memory, etc.

---

## G1 — ZIP Plugin Install (polish what already exists)

### Current state (already in the codebase)
- `PluginsScreen.kt` has both **paste-install** and **`.zip` install** via SAF (`ACTION_OPEN_DOCUMENT`).
- `PluginManager.installFromZip(uri)` extracts to `workspace/plugins/<id>/`, validates `manifest.json` against `PluginManifest`, and runs `PluginValidator` on the entrypoint `.py`.
- Per-plugin enable/disable, uninstall, and a permission inspector are wired up.

### What G1 will add (all small, scoped changes)

| # | Change | File |
|---|---|---|
| G1.1 | **Drag-and-drop / "Open with"**: register an intent filter for `application/zip` + `*/*` with `.fp` extension so the user can tap a `.fp` (or `.zip`) file in any file manager and pick "Forge OS" to install it. | `AndroidManifest.xml`, new `PluginInstallActivity.kt` |
| G1.2 | **SHA-256 manifest field**: if `manifest.json` declares `sha256`, verify the entrypoint hash before install. Reject mismatches. | `PluginManifest.kt`, `PluginValidator.kt` |
| G1.3 | **Signature field (optional)**: if `manifest.json` declares `signature` + `publicKey`, verify Ed25519 over the entrypoint bytes. Show "✓ Signed by <fingerprint>" in the install dialog. Unsigned plugins still install but with a clearer warning. | `PluginValidator.kt` (uses `java.security` / `Bouncycastle` already pulled in) |
| G1.4 | **Permissions diff on update**: when reinstalling a plugin with the same `id`, compute the diff of `permissions[]` vs the installed version and show "NEW PERMISSION REQUESTED: …" before accepting. | `PluginManager.installFromZip` |
| G1.5 | **`.fp` file association**: define a Forge Plugin file format = a `.zip` renamed to `.fp` so users can recognise plugins in their downloads folder. Also lets G1.1 narrow the intent filter to `*.fp` instead of any zip. | `AndroidManifest.xml`, docs |
| G1.6 | **Storage quota check before extraction**: refuse plugins that would push `workspace/plugins/` over a configurable cap (default 50 MB total). | `PluginManager.kt`, `ForgeConfig.kt` |
| G1.7 | **Plugin rollback**: keep the previous version in `workspace/plugins/.bak/<id>/` for one generation; expose "Rollback" button on the plugin row. | `PluginManager.kt`, `PluginsScreen.kt` |

### Deferred (intentionally NOT in G1)
- Marketplace UI / catalog.
- Auto-update from a URL.
- Code-signing CA infrastructure (for now, signature is informational + trust-on-first-use).

---

## G2 — External App API (the big one)

Goal: another Android app on the same phone can talk to Forge OS. Three coexisting surfaces, each with a different trade-off.

### G2.A — Bound Service (AIDL)  ← **the primary surface**

A **bound service** other apps can connect to using `bindService`. Works in both directions (client can register a callback for streamed agent responses).

**New files:**
```
app/src/main/aidl/com/forge/os/api/IForgeOsService.aidl
app/src/main/aidl/com/forge/os/api/IForgeOsCallback.aidl
app/src/main/java/com/forge/os/external/ForgeOsService.kt
app/src/main/java/com/forge/os/external/ExternalApiBridge.kt
app/src/main/java/com/forge/os/external/ExternalCallerRegistry.kt
app/src/main/java/com/forge/os/external/ExternalAuditLog.kt
```

**AIDL surface (first cut):**
```aidl
interface IForgeOsService {
    String getApiVersion();                          // "1.0"
    String listTools();                              // JSON array of tool defs
    String invokeTool(String toolName, String jsonArgs);   // synchronous
    void   invokeToolAsync(String toolName, String jsonArgs, IForgeOsCallback cb);
    void   askAgent(String prompt, String optsJson, IForgeOsCallback cb);
    String getMemory(String key);
    void   putMemory(String key, String value, String tagsCsv);
    String runSkill(String skillId, String jsonArgs);
}

interface IForgeOsCallback {
    void onChunk(String text);
    void onResult(String jsonResult);
    void onError(int code, String message);
}
```

**Manifest:**
```xml
<service
    android:name=".external.ForgeOsService"
    android:exported="true"
    android:permission="com.forge.os.permission.USE_API">
    <intent-filter>
        <action android:name="com.forge.os.api.IForgeOsService" />
    </intent-filter>
</service>

<permission
    android:name="com.forge.os.permission.USE_API"
    android:protectionLevel="signature|dangerous"
    android:label="@string/perm_use_api_label"
    android:description="@string/perm_use_api_desc" />
```

> The `dangerous` flag means the user has to **grant access in Forge OS itself**, not silently via install — see G2.D.

### G2.B — ContentProvider

Read-only (or read/write with permission) surface for apps that prefer SQL-like queries — e.g. read recent conversations, query memory, list plugins.

```
content://com.forge.os.provider/tools
content://com.forge.os.provider/memory
content://com.forge.os.provider/conversations
content://com.forge.os.provider/skills
```

**File:** `app/src/main/java/com/forge/os/external/ForgeOsProvider.kt`

Same `USE_API` permission. Useful for Tasker/Automate-style integrations that already speak ContentProvider.

### G2.C — Intent API (fire-and-forget, easiest for users)

For "I just want to send a prompt and get a notification when done" use-cases (Tasker, Shortcuts, share-sheet).

```
Intent action: com.forge.os.action.ASK
Extras:
   prompt        (String, required)
   conversation  (String, optional)
   model         (String, optional)
   replyTo       (PendingIntent, optional — receives the result)
```

```
Intent action: com.forge.os.action.RUN_TOOL
Extras: tool (String), args (String — JSON)
```

Plus a share-target so any app's share menu offers "Send to Forge OS".

**Files:**
```
app/src/main/java/com/forge/os/external/IntentApiActivity.kt
app/src/main/java/com/forge/os/external/IntentApiReceiver.kt
```

### G2.D — Caller permission UI (the gate)

This is what makes the whole thing safe. Without it, any app could read your memory.

**Flow:**
1. App-X calls `bindService` for the first time.
2. `ForgeOsService.onBind` looks up the calling UID → package name.
3. If the package isn't in `ExternalCallerRegistry`, it returns `null` and posts a high-priority **notification**: *"App-X wants to use Forge OS. Tap to review."*
4. User taps → opens **External API screen** in Forge OS → sees the package name, install source, requested capability set, signing cert SHA-256.
5. User picks per-capability grants:
   - ☐ List tools
   - ☐ Invoke tools (with sub-list of which tools)
   - ☐ Ask agent
   - ☐ Read memory  (☐ all  ☐ tag = …)
   - ☐ Write memory
   - ☐ Run skills (with sub-list)
   - Optional rate limit (calls/min) and daily token budget
6. Grant is persisted in `workspace/system/external_callers.json`.
7. App-X can now bind and call. Every call is appended to `workspace/system/external_audit.jsonl`.

**New screen:** `presentation/screens/external/ExternalApiScreen.kt` (Hub tile → 🌐 "External API").
Shows: pending requests (top), granted apps (with revoke), recent calls (timestamped, with package/tool/result-size).

### G2.E — Documentation + sample client

Ship a tiny sample app/source so a third-party dev can copy-paste and integrate in 5 minutes:

```
forge-os-sample-client/
   build.gradle
   src/main/AndroidManifest.xml      (declares <uses-permission USE_API/>)
   src/main/java/.../MainActivity.kt (binds, calls invokeTool)
```

Plus `forge-os/docs/EXTERNAL_API.md` covering:
- AIDL setup (copying the `.aidl` files into your app)
- Permission declaration
- Bind/unbind lifecycle
- Sync vs async tool calls
- ContentProvider schemas
- Intent API extras
- Error codes
- Versioning policy (`getApiVersion()` SemVer)

---

## File summary

### New files (~22)
```
External API (G2):
  app/src/main/aidl/com/forge/os/api/IForgeOsService.aidl
  app/src/main/aidl/com/forge/os/api/IForgeOsCallback.aidl
  app/src/main/java/com/forge/os/external/ForgeOsService.kt
  app/src/main/java/com/forge/os/external/ForgeOsProvider.kt
  app/src/main/java/com/forge/os/external/ExternalApiBridge.kt
  app/src/main/java/com/forge/os/external/ExternalCallerRegistry.kt
  app/src/main/java/com/forge/os/external/ExternalCaller.kt
  app/src/main/java/com/forge/os/external/ExternalAuditLog.kt
  app/src/main/java/com/forge/os/external/IntentApiActivity.kt
  app/src/main/java/com/forge/os/external/IntentApiReceiver.kt
  app/src/main/java/com/forge/os/presentation/screens/external/ExternalApiScreen.kt
  app/src/main/java/com/forge/os/presentation/screens/external/ExternalApiViewModel.kt

Plugin polish (G1):
  app/src/main/java/com/forge/os/presentation/PluginInstallActivity.kt

Sample + docs:
  forge-os-sample-client/ (mini Android Studio project, ~6 files)
  forge-os/docs/EXTERNAL_API.md
```

### Modified files (~10)
```
AndroidManifest.xml             — permission + service + provider + intent filters
PluginManifest.kt               — sha256, signature, publicKey fields
PluginValidator.kt              — hash + signature checks
PluginManager.kt                — quota, rollback, perms diff
ForgeConfig.kt                  — plugin storage cap, external API toggle
HubScreen.kt                    — new "External API" tile
MainActivity.kt                 — new route
di/AppModule.kt                 — providers for ExternalCallerRegistry, ExternalAuditLog, ExternalApiBridge
domain/agent/ToolRegistry.kt    — surface tool list/invoke for external use
strings.xml                     — permission labels, screen strings
```

---

## Build / risk notes

- **AIDL** requires `buildFeatures { aidl true }` in `app/build.gradle` (currently off — one-line add).
- The exported service/provider must declare `android:exported="true"` and a custom permission. We use `signature|dangerous` so it can't be auto-granted at install.
- `ExternalAuditLog` writes to the same `workspace/system/` folder as the existing `tool_audit.jsonl`, keeping the audit story unified.
- API versioning is SemVer via `getApiVersion()`. Breaking changes bump major; the service remains backward-compatible within a major.
- Performance: AIDL is sync IPC; long agent runs MUST go through `invokeToolAsync` / `askAgent` with the callback so the calling app's binder thread isn't blocked.

## Smoke test (when built)

1. Install Forge OS, then install the sample client.
2. Sample client tries to bind → notification appears in Forge OS.
3. Tap → External API screen shows the request → grant "Invoke tools: shell.exec, fs.read; Read memory: tag=public".
4. Sample client `invokeTool("fs.read", {"path":"/notes/today.md"})` → returns the file content.
5. Revoke grant → next call returns error `403 PERMISSION_DENIED`.
6. Audit screen shows both calls + the deny.
7. Tasker test: build a Task with action `com.forge.os.action.ASK`, extra `prompt = "summarise my day"`, replyTo PendingIntent → result lands in Tasker variable.

## Open questions for you

1. **Plugin file extension** — `.fp` (Forge Plugin) or stick with plain `.zip`?
2. **Default rate limit** for external callers — proposal: 30 calls/min, 50k tokens/day per granted app. OK?
3. **Signature requirement** — should unsigned plugins be allowed at all, or warn-only (current proposal: warn-only)?
4. **Should the External API be off by default** in `ForgeConfig`, requiring the user to flip a master switch in Settings before any app can even request access? (I recommend yes.)
