# Forge OS — Phase A drop

Drop-in replacement files for **Phase A (M1 — Unblock chat)** of the Part 2 plan.

## What's in this drop

| File | Status | What changed |
|---|---|---|
| `domain/security/SecureKeyStore.kt` | **REPLACE** | Added 6 new built-in providers (Gemini, xAI, DeepSeek, Mistral, Together, Cerebras), each with `schema` + `defaultModel`. Added `saveCustomKey/getCustomKey/...` for custom endpoints. `getActiveProvider()` priority order extended. |
| `domain/security/ProviderSpec.kt` | **NEW** | Sealed class `Builtin` / `Custom` — represents a concrete `(provider, model)` pair the chat layer uses. |
| `domain/security/CustomEndpointRepository.kt` | **NEW** | JSON-file persistence for user-defined OpenAI/Anthropic-compatible endpoints. Stored at `workspace/system/custom_endpoints.json`. Keys stored separately in `SecureKeyStore`. |
| `data/api/ApiError.kt` | **NEW** | Typed `ApiError` + `ApiCallException`. Carries `httpCode`, `providerCode`, `requestId`, etc. |
| `data/api/ApiCallLog.kt` | **NEW** | In-memory ring buffer (200 entries) of every API call for the diagnostics screen. |
| `data/api/AiApiManager.kt` | **REPLACE** | Major rewrite: dispatches by `ProviderSchema`, accepts optional `ProviderSpec` override, runs on `Dispatchers.IO`, **retries once** on 5xx/429 with backoff, throws `ApiCallException` with structured info, logs every call. Adds `availableSpecs()` listing every keyed provider + custom endpoint. |
| `domain/agent/ReActAgent.kt` | **REPLACE** | `run()` now accepts `spec: ProviderSpec? = null`. `AgentEvent.Error` now carries an optional `ApiError` for structured UI rendering. |
| `di/AppModule.kt` | **REPLACE** | Provides `CustomEndpointRepository` + `ApiCallLog`; updated `provideAiApiManager` signature. |
| `presentation/screens/ChatViewModel.kt` | **REPLACE** | Holds `availableSpecs / selectedSpec / autoRoute` state. Passes the chosen spec into `ReActAgent.run()`. Surfaces `errorDetail` on assistant error messages. |
| `presentation/screens/ChatScreen.kt` | **REPLACE** | Adds `ModelPickerChip` row under the header (dropdown with all keyed providers + an "Auto-route" entry). Inline error bubble shows provider/HTTP code and a "Fix in Settings →" link. |
| `presentation/screens/SettingsViewModel.kt` | **REPLACE** | Adds custom-endpoint CRUD methods. |
| `presentation/screens/SettingsScreen.kt` | **REPLACE** | Lists all 11 built-in providers (each with a key card), a new **CUSTOM ENDPOINTS** section with an "Add" dialog (name / URL / default model / schema / key), and a top-right BugReport icon to navigate to Diagnostics. |
| `presentation/screens/DiagnosticsScreen.kt` | **NEW** | API call log viewer (timestamp, provider, model, HTTP code, duration, tokens, error message, request URL). Includes `DiagnosticsViewModel`. |

## Wiring you still need to do

1. **Add a Diagnostics route** to `MainActivity` nav graph. Sketch:

   ```kotlin
   composable("diagnostics") {
       DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
   }
   ```

   And update the `SettingsScreen` call site to pass:
   ```kotlin
   SettingsScreen(
       onNavigateBack = { navController.popBackStack() },
       onNavigateToDiagnostics = { navController.navigate("diagnostics") }
   )
   ```

2. **Build & run.** The first launch will create `workspace/system/custom_endpoints.json` (empty array). Existing keys are untouched (same pref keys).

3. **Test path:**
   - Add an OpenAI key in Settings → confirm "Auto-route" works.
   - Tap the model chip under the header → pick "Anthropic · …" or any other → confirm the next message uses that model.
   - Add a custom endpoint pointing at e.g. `https://api.x.ai/v1/` (or a local LM Studio) → confirm it appears in the model picker.
   - Force a 401 by entering a bogus key → confirm the inline error bubble shows `provider=… http=401 code=…`.
   - Open Settings → BugReport icon → verify the diagnostics log shows the failed call.

## What this completes from the Part 2 plan

- [x] **A1** Model picker with persistence + auto-route toggle
- [x] **A2** Typed errors, inline display, IO dispatcher, 1× retry on 5xx/429, diagnostics log
- [x] **A3** 6 new built-in providers + custom endpoint CRUD with both schemas

Mark these off in `exports/forge-os-part2-plan.md` after you confirm the build is green.

## Phase B is next

Phase B (theming — light/dark/system) is small (4 files, ~20 minutes of work). Confirm Phase A compiles and I'll ship it.
