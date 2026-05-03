# Forge OS — External API (Phase G2)

Other Android apps on the same device can drive Forge OS through three surfaces.
All three live behind the same authorisation flow:

1. The caller's APK must declare `com.forge.os.permission.USE_API` (a `dangerous`
   permission Forge OS itself defines).
2. The user must turn the master switch **On** in *Hub → 🌐 External API*.
3. The user must grant the specific capabilities (list tools, invoke tools,
   ask agent, read/write memory, run skills) for that exact package.

If any step is missing the call is denied with a structured error and an entry
is appended to `workspace/system/external_audit.jsonl`.

Defaults:

* Off by default (master switch).
* 30 calls/min and 50 000 tokens/day per granted app.
* Unsigned plugins (relevant to G1) install with a warn-only banner.

---

## Surface 1 — Bound AIDL service (recommended)

```kotlin
// In the caller app:
val intent = Intent("com.forge.os.api.IForgeOsService")
    .setPackage("com.forge.os")
bindService(intent, conn, Context.BIND_AUTO_CREATE)
```

The binder implements `com.forge.os.api.IForgeOsService` (see the AIDL files in
`app/src/main/aidl/com/forge/os/api/`).

| Method | Purpose |
| --- | --- |
| `getApiVersion()` | Returns `"1.0"`. |
| `listTools()` | JSON array of `{name, description}` for tools the caller may invoke. |
| `invokeTool(name, jsonArgs)` | Synchronous; returns `{"ok":bool,"output":string,"error"?:string}`. |
| `invokeToolAsync(name, jsonArgs, cb)` | Same, but result delivered via `IForgeOsCallback.onResult`. |
| `askAgent(prompt, optsJson, cb)` | Streams response chunks via `cb.onChunk`, final via `cb.onResult`. |
| `getMemory(key)` | Read a long-term memory value. Returns `""` if absent or denied. |
| `putMemory(key, value, tagsCsv)` | Store a value, tagged with `external:<pkg>`. |
| `runSkill(skillId, jsonArgs)` | Run an installed skill (plugin tool) by id. |

Error codes on the callback: **400** bad args, **403** permission denied,
**429** rate limited, **500** internal.

---

## Surface 2 — ContentProvider

For Tasker, Automate, or anything that already speaks `content://`:

* `content://com.forge.os.provider/tools` → cursor of `name, description`
* `content://com.forge.os.provider/skills` → cursor of `id, name`
* `content://com.forge.os.provider/memory/<key>` → cursor of `key, value`
  - Insert with values `{"value":..., "tags":"comma,separated"}` to write.

Same permission gate applies (`readPermission`/`writePermission` =
`com.forge.os.permission.USE_API`).

---

## Surface 3 — Intent (fire-and-forget)

```kotlin
val ask = Intent("com.forge.os.action.ASK")
    .setPackage("com.forge.os")
    .putExtra("prompt", "summarise my calendar today")
    .putExtra("replyTo", PendingIntent.getBroadcast(...))  // optional
startActivity(ask)
```

Or share text from any app — the share sheet entry "Ask Forge" picks the
selected text up as the prompt.

`com.forge.os.action.RUN_TOOL` runs a single tool: extras `tool` (name) and
`args` (JSON string).

The result is returned in `EXTRA_RESULT` of the supplied `replyTo`
PendingIntent as the same JSON envelope used by `invokeTool`.

---

## Sample client

`forge-os-sample-client/` contains a one-screen app that binds to the service
and lists the tools available to it. It's useful as an integration smoke test
once the user has granted it access.
