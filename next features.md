# Next Features — Forge OS

This document collects every feature idea discussed during the session (user-suggested + assistant suggestions). Items are grouped and include short MVP notes and risks where relevant.

---

## ✅ Completed Features

| # | Feature | Status |
|---|---------|--------|
| 1 | Separate memory / individualized channels | ✅ Done |
| 2 | forge-os Python package (developer SDK) | ✅ Done — `forge_sdk.py` + `ForgeHttpServer` |
| 6 | "Hello Forge" hotword wake | ✅ Done |
| 7 | Pip package bundling | ✅ Done — `python_install` tool (pure-Python wheels → `workspace/python_packages/`, auto sys.path); native pkgs stay build-time |
| 8 | Secrets as env variables | ✅ Done |
| 9 | Browser reveal (headless → visible) | ✅ Done — `browser_reveal` |
| 10 | Notification click actions | ✅ Done — `NotificationActionReceiver` |
| 11 | Dark/light theme tables | ✅ Done — `ForgePalette` theme-aware |
| 12 | Multimodal chat (images/video/audio) | ✅ Done — `attachmentPath` + `FileAttachmentBubble` |

---

## 🔲 Remaining User-suggested Features

3. "Grab & run" anti‑theft protection
   - MVP: opt-in Missing Mode: lock device UI, collect last-known location/screenshots, send secure packet to trusted contacts/Telegram, allow remote wipe. Foreground service + encrypted keystore auth.
4. Map visited areas and detect room boundaries
   - MVP: background sampler (GPS/Wi‑Fi/BLE), DBSCAN clustering into "areas" with start/end times, simple UI list and export.
5. Auto-respond to calls when phone missing
   - MVP: Missing profile that auto-replies via SMS or plays TTS on incoming calls for trusted contacts. Configurable templated responses.

---

## Assistant pragmatic suggestions (everyday-focused)
- One-tap automations: pre-built recipes (daily summary, photo backup) with minimal permissions.
- Instant suggestions & contextual quick-actions surfaced in notifications/chat.
- Pre-warmed runtime: start Python/Forge bridge early to reduce first-run latency.
- Tiny on-device model cache for commonsense replies / fallback.
- Guided onboarding and recipe gallery.
- Permission profiles (Safe / Standard / Power-user).
- Background prioritization and energy-aware scheduling.
- Searchable help + example plugins in-app.
- Opt-in anonymized telemetry to tune features.

---

## Security / platform improvements suggested earlier
- Harden sandbox: stricter AST/module allowlist, CPU/memory/time quotas, syscall limits.
- Plugin signing & verification + trust store.
- Granular plugin permission UI and runtime prompts.
- Reproducible builds & dependency pinning.
- CI/CD: GitHub Actions + fastlane to build and test and deploy.
- Automated tests: unit, instrumentation, Python sandbox integration tests.
- Backup/restore and workspace export/import.
- Security audits and dependency scanning.

---

## Creative alternatives (non-obvious features)
- Context-aware automation recipes: packaged multi-tool recipes for everyday tasks (e.g., inbox triage, nightly backup).
- Plugin composition DSL: JSON/YAML to wire plugins into pipelines without code.
- Live replay debugger for sandboxed Python: record runs, snapshots, step-through replay.
- Offline skill marketplace: peer-to-peer plugin discovery over LAN with signed manifests.
- Adaptive resource governor: dynamic caps per-plugin depending on battery/thermal state.
- Conversation templates with guardrails: reusable, auditable chat flows and review UI.

---

## Additional practical ideas (from the later brainstorm)
- Personalized daily briefings (AI-curated highlights + action links).
- One-tap "privacy lockdown" (instant data lock, network cutoff).
- Local NLU for offline intent handling (small intent DB).
- Visual automation builder (drag-drop flow to plugin chains).
- Ready-made plugin templates and one-click install gallery.
- Multimodal tools: camera OCR, auto-fill from screenshots.
- Voice biometrics for trusted actions and quick unlock.
- Adaptive model cache & federated opt-in learning.
- Secure P2P plugin sharing over LAN (mDNS + signed manifests).
- Auto-organize photos/docs by AI-detected events/places.
- Emergency recovery chain: auto-notify trusted contacts with proof.
- Accessibility shortcuts (large-text, TTS workflows, gesture triggers).

---

## Suggested priority (remaining items)
1. Pre-warmed runtime & small on-device model cache — big UX speed wins.
2. One-tap automations, recipe gallery, and visual automation builder — makes power features accessible.
3. Anti‑theft "Grab & run" + Missing Mode — security feature with legal/UX constraints.
4. Mapping visited areas, auto-respond when missing, and emergency features — useful but sensitive; require careful privacy defaults.
5. Live replay sandbox debugger & adaptive resource governor — developer-friendly and robust.

---

## Next actions (pick one)
- Create tracked TODOs in the repository for top N items (I can add to the session DB todos table).
- Draft a starter PR for one chosen MVP (e.g., forge-os Python SDK skeleton or per-channel memory).
- Create design doc(s) for privacy-sensitive features (Missing Mode, area mapping, hotword) to vet legal/UX constraints.


*Document generated by Copilot CLI — say which next action to take and I will implement it.*
