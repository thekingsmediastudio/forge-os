# UI Overhaul — Sleek & Modern Forge OS

Target: unified premium "modern AI OS" aesthetic across all screens.

## Phase 1 — Design system foundation
- [x] ForgePalette.kt — refine dark/light palettes + add scrim token
- [x] Color.kt — sync constants with new palette
- [x] Type.kt — refine type scale
- [x] Theme.kt — add Material3 Shapes + align status bar

## Phase 2 — Shared components overhaul
- [x] DomainTheme.kt — redesign ModuleScaffold + ModuleCard + SectionHeader
- [x] ModernComponents.kt — harmonize ModernHeader/Card/Button/TextField

## Phase 3 — Flagship screens polish
- [ ] ModernChatScreen.kt
- [ ] ModernHubScreen.kt
- [ ] ModernOnboardingScreen.kt
- [ ] ModernStatusScreen.kt
- [ ] SettingsScreen.kt

## Phase 4 — Module screen standardization
- [ ] Tools, Memory, Agents, Conversations, Cron, Plugins (high-traffic first)
- [ ] Skills, Projects, Snapshots, Cost, Server, Doctor
- [ ] Remaining module screens sweep

## Follow-up
- [ ] `./gradlew :app:compileDebugKotlin` verification

