# Forge OS — Phase C drop (Workspace browser)

Closes M3 / Phase C in `PART2_PLAN.md` — file explorer (C1), inline viewer/editor (C2), and quick actions (C3) all land in this drop.

## What's in this drop

| File | Status | What changed |
|---|---|---|
| `data/sandbox/SandboxManager.kt` | **REPLACE** | Added: `resolveSafe()` (public path resolver, used by FileProvider/share/Coil), `readBytes()`, `searchFiles()` (current-dir or recursive), `mkdir()`, `createEmptyFile()`, `rename()` (also serves as move), `moveToTrash()` (soft delete to `workspace/.trash/<timestamp>_<name>`), `deleteRecursive()`, `exists()`. All inherit the existing sandbox-escape protection. |
| `presentation/screens/WorkspaceViewModel.kt` | **REPLACE** | Now exposes a single `WorkspaceUiState` (cwd, entries, sort, query, recursive, selection, info, message/error). Implements `openDirectory`, `navigateUp`, `setSort`, `setQuery`, `setRecursiveSearch`, multi-select toggle/clear, `newFile`, `newFolder`, `rename`, `deleteToTrash`, `deleteSelectionToTrash`, `emptyTrashEntry`. Old `files` / `workspaceInfo` flows are kept as compatibility shims. |
| `presentation/screens/WorkspaceScreen.kt` | **REPLACE** | Real explorer UI: storage card, breadcrumb (with up button + workspace root chip + clickable path segments), search box with recursive switch, sort sheet (Name / Date / Size / Type, tap-to-flip direction), per-row overflow menu (Rename / Move to trash / Share / Select), long-press / multi-select with bulk delete, empty-state, snackbar feedback. Tapping a folder descends; tapping a file routes to the viewer. |
| `presentation/screens/FileViewerViewModel.kt` | **NEW** | Detects file kind (TEXT / IMAGE / BINARY) by extension first, falls back to NUL-byte sniffing on the first 512 bytes. Loads text via `SandboxManager.readFile`, hex preview via `readBytes`. `save(force)` writes back through `SandboxManager.writeFile`; out-of-sandbox files require explicit confirmation. `isDirty` drives the Save button. |
| `presentation/screens/FileViewerScreen.kt` | **NEW** | Top bar with Save (text) and "Open with…" (binary/image) actions. Text mode: monospace `BasicTextField` for editing, syntax-coloured preview while pristine. Image mode: Coil `AsyncImage` + pinch-zoom + pan via `detectTransformGestures`. Binary mode: header card (size + MIME) plus a 4 KB hex dump in a scrollable mono Text. |
| `presentation/screens/SyntaxHighlighter.kt` | **NEW** | Tiny regex-based highlighter for `json` / `md` / `py` / `kt` / `yaml`. Falls back to plain text for everything else. Themed colours pulled from `MaterialTheme.colorScheme` so light/dark from Phase B both look right. |
| `presentation/MainActivity.kt` | **REPLACE** | Adds `fileViewer/{path}` route (path is URL-encoded). Injects `SandboxManager` so the workspace screen can hand it to share/open intents. |
| `app/src/main/AndroidManifest.xml` | **REPLACE** | Registers `androidx.core.content.FileProvider` under `${applicationId}.fileprovider` so Share / Open with… work. No new permissions added. |
| `app/src/main/res/xml/file_paths.xml` | **NEW** | FileProvider path config: exposes only `files/workspace/`. |
| `app/build.gradle` | **PATCH** | `implementation 'io.coil-kt:coil-compose:2.5.0'` (image loading for the viewer). Nothing else touched. |
| `_FORGE_PLAN/PART2_PLAN.md` | **PATCH** | All Phase C boxes (C1.1–C3.4) ticked. Progress log row appended. |
| `_FORGE_PLAN/CURRENT_STATUS.md` | **REPLACE** | Snapshot bumped to "post Phase C drop"; "you are here" pointer moved to M3; Phase D listed as next. |

## Wiring you still need to do

None. `MainActivity` already routes the new viewer; `AndroidManifest.xml` already declares the FileProvider. First Gradle sync after applying this drop will pull `coil-compose:2.5.0`.

## What this completes from the Part 2 plan

- [x] **C1.1** `WorkspaceViewModel.listDirectory(path)` — implemented as `openDirectory(path)` backed by `SandboxManager.listFiles()`.
- [x] **C1.2** Tree/list view in `WorkspaceScreen` with breadcrumb navigation.
- [x] **C1.3** Sort options: Name / Date / Size / Type (tap active option to flip direction).
- [x] **C1.4** Search box (current dir, plus recursive toggle).
- [x] **C2.1** New `FileViewerScreen` route (`fileViewer/{path}`).
- [x] **C2.2** Type detection by extension with binary fallback sniff.
- [x] **C2.3** Text viewer/editor with basic syntax colour for `.json` / `.md` / `.py` / `.kt` / `.yaml`.
- [x] **C2.4** Image viewer (Coil) with pinch-to-zoom + pan.
- [x] **C2.5** Binary viewer: size + MIME + 4 KB hex preview + "Open with…" Android intent.
- [x] **C2.6** Save button → `SandboxManager.writeFile()`. Out-of-sandbox confirm dialog.
- [x] **C3.1** New file / new folder dialogs (top-bar `+` menu).
- [x] **C3.2** Rename + delete-to-`workspace/.trash/` (timestamped to avoid collisions). Move is reuse of `rename()` to a different parent.
- [x] **C3.3** Long-press → row overflow → Share via Android intent (FileProvider).
- [x] **C3.4** Multi-select with bulk move-to-trash.

## Test path

1. `./gradlew :app:assembleDebug` — should compile clean. First run pulls Coil from Maven Central.
2. Launch the app → Chat → top-bar Workspace icon → **Workspace** screen opens.
3. **C1 (browse + sort + search):**
   - Tap into `system/` → breadcrumb updates to `workspace › system`. Tap the `workspace` chip → back at root. Use the up arrow → also goes back.
   - Sort menu (top bar) → switch to **Date**, then tap Date again → arrow flips to descending.
   - Type a partial name in the search box → list filters in place. Toggle **Recursive** → matches now include nested files.
4. **C2 (viewer/editor):**
   - Open `system/config.json` (created by Phase A/B) → should render with syntax colour. Edit any character → syntax colour drops out (raw editor) and the **Save** icon enables. Tap Save → snackbar shows `Saved`.
   - Drop a `.png` into `workspace/` (e.g. via adb push or shell tool) → tap it → image renders. Pinch to zoom, drag to pan.
   - Drop any `.bin` (or just rename a `.so` from the apk) → tap it → 4 KB hex dump shows. Tap the **Open with…** icon → Android system chooser appears.
5. **C3 (quick actions):**
   - `+` → New folder → name it `scratch` → it appears at top of the list.
   - On any row: overflow menu → Rename → change the name → snackbar `Renamed`.
   - Overflow → Move to trash → confirm → check `workspace/.trash/` (visible in the explorer) for `<timestamp>_<name>`.
   - Overflow → Share on a file → Android share sheet appears.
   - Long-press a row → checkbox mode engages → tick more rows → top bar switches to "N selected" → trash icon → all moved at once.

## Phase D is next

Phase D in the plan is the domain UIs (tools, plugins, cron, memory, agents, projects, skills). Recommended starting slice is **D1 (tools & permissions)** because it has the highest trust impact and reuses the same list-and-detail pattern landed here in C.
