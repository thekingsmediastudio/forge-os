# Forge OS — Phase B drop (Theming)

Closes M2 / Phase B in `PART2_PLAN.md`.

## What's in this drop

| File | Status | What changed |
|---|---|---|
| `presentation/theme/ThemeMode.kt` | **NEW** | `enum class ThemeMode { LIGHT, DARK, SYSTEM }` with a `displayName` helper. Serializable so it can live inside `ForgeConfig`. |
| `presentation/theme/Theme.kt` | **REPLACE** | `ForgeTheme` now takes `themeMode: ThemeMode = SYSTEM` (was `darkTheme: Boolean`). SYSTEM falls back to `isSystemInDarkTheme()`. Status bar + light icons follow the resolved mode. |
| `domain/config/ForgeConfig.kt` | **PATCH** | Added `appearance: AppearanceSettings = AppearanceSettings()` plus a new `AppearanceSettings(themeMode = SYSTEM)` data class. Backward-compatible — `ignoreUnknownKeys = true` means existing `config.json` files load fine and pick up the default. |
| `domain/config/ConfigRepository.kt` | **PATCH** | New `themeMode: StateFlow<ThemeMode>` (primed from disk in `init`), updated by every `save(...)`. New `setThemeMode(mode)` helper that writes through `save()`. |
| `presentation/MainActivity.kt` | **REPLACE** | `@Inject` `ConfigRepository`, `collectAsState` on `themeMode`, and pass it into `ForgeTheme(themeMode = ...)`. Whole nav graph reactively recomposes on theme changes. |
| `presentation/screens/SettingsViewModel.kt` | **PATCH** | `ConfigRepository` injected. Exposes `themeMode: StateFlow<ThemeMode>` and `setThemeMode(ThemeMode)` (writes on `Dispatchers.IO`). |
| `presentation/screens/SettingsScreen.kt` | **PATCH** | New top-of-list `APPEARANCE` section with an `AppearanceCard` containing three radio buttons (Light / Dark / Follow system). Uses the existing palette + monospace styling. |

## Wiring you still need to do

None. `MainActivity` is already updated — no manual nav graph edit this time.

## What this completes from the Part 2 plan

- [x] **B1.1** Light/dark `ColorScheme`s already existed; verified both wired in `Theme.kt`.
- [x] **B1.2** `Theme.kt` switches on a `ThemeMode` arg.
- [x] **B1.3** `themeMode` lives in `ConfigRepository` (default `SYSTEM`), stored in `ForgeConfig.appearance`.
- [x] **B1.4** Settings → Appearance section with three radio buttons.
- [x] **B1.5** Root composable observes the mode via `StateFlow` so changes apply immediately, no restart.

## Test path

1. Launch the app — should come up in your system theme (default).
2. Open Settings → top of list now has `APPEARANCE` with three radio buttons.
3. Tap **Light** → entire app flips to light, status bar icons go dark, and the green save toast says `Theme set to Light`.
4. Tap **Dark** → flips back to the dark palette.
5. Kill the app and relaunch → it remembers the last selection (persisted in `workspace/system/config.json`).
6. Tap **Follow system** → toggle Android's system dark mode (Quick Settings) → the app should follow live.

## Phase C is next

Phase C in the plan is the workspace browser (file explorer + viewer/editor). Confirm Phase B compiles and tell me which slice you want first — I'd suggest **C1 (read-only explorer + breadcrumb)** before tackling the editor in C2.
