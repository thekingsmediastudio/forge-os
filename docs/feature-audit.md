# Feature Audit — next features.md

**Date:** 2026-08-02
**Score: 10 DONE, 9 PARTIAL, 19 NOT DONE** out of 38 total items

---

## User-Suggested Priorities

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | Separate memory / individualized channels | ✅ DONE | ChannelManager, ChannelSwitcher, per-channel sessions, export/purge |
| 2 | forge-os Python SDK (pip package) | ❌ NOT DONE | No setup.py/pyproject.toml; sample client is Android/Kotlin only |
| 3 | Anti-theft "Grab & run" protection | ❌ NOT DONE | No missing mode, theft detection, or remote wipe |
| 4 | Map visited areas / room boundaries | ❌ NOT DONE | No DBSCAN clustering or background location sampling |
| 5 | Auto-respond to calls when missing | ❌ NOT DONE | Auto-reply exists for Telegram only, not phone calls |
| 6 | "Hello Forge" hotword wake | 🟡 PARTIAL | Text parsing of "forge"/"hey forge" post-recognition; no always-listening low-power model (Picovoice/Vosk) |
| 7 | Pip packages bundled in app | 🟡 PARTIAL | `python_pip_install` tool works at runtime via Chaquopy; no dedicated user folder or auto-bundle mechanism |
| 8 | Secrets as Python env variables | ❌ NOT DONE | NamedSecretRegistry/SecureKeyStore exist but no env injection into Python sandbox |
| 9 | Reveal headless browser in same state | 🟡 PARTIAL | HeadlessBrowser + BrowserSessionManager with cookie sharing exist; no explicit "reveal to user" tool |
| 10 | Notification click actions | ✅ DONE | NotificationActionReceiver, NotificationActionRegistry, PendingIntent deep links |
| 11 | Tables dark-theme only | ✅ DONE | MarkdownText.kt uses forgePalette tokens; light/dark palettes defined |
| 12 | Send media in main chat | ✅ DONE | attachmentPath/attachmentMime on messages, FileAttachmentBubble, Telegram file/voice send |

---

## Assistant Pragmatic Suggestions

| Feature | Status | Notes |
|---------|--------|-------|
| One-tap automations / recipes | ✅ DONE | 20+ BuiltInRecipes, RecipesScreen with search/filter/create, "Use in Chat" flow |
| Instant suggestions / quick actions | ✅ DONE | EmptyState suggestion chips, ProactiveWorker, slash commands |
| Pre-warmed runtime | ❌ NOT DONE | Python/Chaquopy initialized lazily on first use |
| On-device model cache | 🟡 PARTIAL | `model_cache_refresh` refreshes API model list; no local inference fallback |
| Guided onboarding / recipe gallery | ✅ DONE | ModernOnboardingScreen, PermissionOnboardingScreen, TutorialManager, recipe gallery |
| Permission profiles | ✅ DONE | PermissionManager (GUEST/USER/POWER/ADMIN), safe mode toggle, per-tool UI |
| Energy-aware scheduling | 🟡 PARTIAL | `setRequiresBatteryNotLow` on workers; no dynamic thermal/battery governor |
| Searchable help / plugins | 🟡 PARTIAL | CommandPalette, hub search keywords; no dedicated in-app help docs |
| Telemetry | ❌ NOT DONE | No analytics collection; explicitly noted as unchecked in plan docs |

---

## Security / Platform

| Feature | Status | Notes |
|---------|--------|-------|
| Hardened sandbox | ✅ DONE | SecurityPolicy, Python AST import checking, SandboxManager quotas, AgentControlPlane |
| Plugin signing | 🟡 PARTIAL | PluginValidator with banned imports scan; no Ed25519 signature verification / trust store |
| Granular plugin permissions | 🟡 PARTIAL | PluginManifest permission profile + UI display; no runtime permission prompts |
| Reproducible builds | ❌ NOT DONE | No dependency locking or verification in build.gradle |
| CI/CD | ✅ DONE | GitHub Actions android.yml, fastlane metadata, RELEASE.md |
| Automated tests | 🟡 PARTIAL | JUnit/Compose test deps declared; some test files exist but coverage is thin |
| Backup/restore | ✅ DONE | MemoryArchive export, workspace export/import |
| Security audits | ❌ NOT DONE | No dependency scanning or audit tooling |

---

## Creative Alternatives (all NOT DONE)

| Feature | Status |
|---------|--------|
| Context-aware automation recipes | ❌ NOT DONE |
| Plugin composition DSL | ❌ NOT DONE |
| Live replay sandbox debugger | ❌ NOT DONE |
| Offline skill marketplace (P2P LAN) | ❌ NOT DONE |
| Adaptive resource governor | ❌ NOT DONE |
| Conversation templates with guardrails | ❌ NOT DONE |

---

## Additional Practical Ideas (all NOT DONE)

| Feature | Status |
|---------|--------|
| Personalized daily briefings | ❌ NOT DONE |
| One-tap privacy lockdown | ❌ NOT DONE |
| Local NLU for offline intents | ❌ NOT DONE |
| Visual automation builder | ❌ NOT DONE |
| Plugin templates / one-click gallery | ❌ NOT DONE |
| Multimodal tools (camera OCR) | ❌ NOT DONE |
| Voice biometrics | ❌ NOT DONE |
| Adaptive model cache / federated learning | ❌ NOT DONE |
| P2P plugin sharing (mDNS) | ❌ NOT DONE |
| Auto-organize photos/docs | ❌ NOT DONE |
| Emergency recovery chain | ❌ NOT DONE |
| Accessibility shortcuts | ❌ NOT DONE |

---

## Summary

The **core UX and platform foundations are solid** — channels, recipes, onboarding, permissions, sandbox, CI/CD, notifications, theming, and media sharing are all done. The remaining items are mostly **advanced/new features** (Python SDK, anti-theft, location mapping, hotword, P2P, etc.) and **polish gaps** (pre-warmed runtime, telemetry, reproducible builds, runtime plugin permissions).
