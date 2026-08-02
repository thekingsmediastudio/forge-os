# Next Features — Forge OS

This document collects every feature idea discussed during the session (user-suggested + assistant suggestions). Items are grouped and include short MVP notes and risks where relevant.

---

## User-suggested priorities (direct request)
1. Separate memory / individualized channels
   - MVP: per-channel namespaces (workspace/companion/<channel_id>), UI channel switch, scoped embedding index and search. Provide channel export/purge.
2. forge-os Python package (developer SDK)
   - MVP: pip-installable module that talks to Forge via local HTTP socket or Unix/ADB socket exposing tool_call, memory APIs, and short-lived auth tokens. Examples and templates included.
3. "Grab & run" anti‑theft protection
   - MVP: opt-in Missing Mode: lock device UI, collect last-known location/screenshots, send secure packet to trusted contacts/Telegram, allow remote wipe. Foreground service + encrypted keystore auth.
4. Map visited areas and detect room boundaries
   - MVP: background sampler (GPS/Wi‑Fi/BLE), DBSCAN clustering into "areas" with start/end times, simple UI list and export.
5. Auto-respond to calls when phone missing
   - MVP: Missing profile that auto-replies via SMS or plays TTS on incoming calls for trusted contacts. Configurable templated responses.
6. "Hello Forge" hotword wake
   - MVP: integrate a small on-device wake word model (Picovoice / Vosk style) in a low-power audio service, show assistant UI on detection.
7. a pip package bundled in the app that uses a dedicated folder downloads python packages there. the packages are automatically added to bndled packages and can be used
8. allow secrets to be accessible by the python code as env variables..
9. allow agent to reveal headless browser in same state such as if it needs help it can request.
10. our notifications on click they dont do things.....

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

## Suggested priority (combined recommendations)
1. Separate memory / individualized channels — high impact for personalization.
2. forge-os Python SDK — enables developers to integrate AI in existing Python projects quickly.
3. Pre-warmed runtime & small on-device model cache — big UX speed wins.
4. One-tap automations, recipe gallery, and visual automation builder — makes power features accessible.
5. Hotword wake ("Hello Forge") — natural interaction model.
6. Live replay sandbox debugger & adaptive resource governor — developer-friendly and robust.
7. Anti‑theft "Grab & run" + Missing Mode — security feature with legal/UX constraints.
8. Mapping visited areas, auto-respond when missing, and emergency features — useful but sensitive; require careful privacy defaults.

---

## Next actions (pick one)
- Create tracked TODOs in the repository for top N items (I can add to the session DB todos table).
- Draft a starter PR for one chosen MVP (e.g., forge-os Python SDK skeleton or per-channel memory).
- Create design doc(s) for privacy-sensitive features (Missing Mode, area mapping, hotword) to vet legal/UX constraints.


*Document generated by Copilot CLI — say which next action to take and I will implement it.*
