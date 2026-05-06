# Forge OS

**Forge OS** is an open-source AI operating system for Android, built for powerful on-device automation, extensibility, and seamless integrations.

It enables developers and creators to build, automate, and control workflows using plugins, agents, and chat interfaces.

---

## 🚀 Features

* **ReAct-powered AI agent** with 200+ built-in tools (browser, file I/O, system control, etc.)
* **Extensible plugin system** (Python, Java/Kotlin, JavaScript)
* **Telegram integration** for remote and chat-based control
* **Headless browser automation** with shared sessions/cookies
* **Git-backed workspace** (snapshots, cron jobs, automation workflows)
* **Memory & skill store** for reusable scripts and knowledge

---

## ⚡ Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/thekingsmediastudio/forge-os.git
cd forge-os

# 2. Open in Android Studio (Flamingo+ recommended)
# Import the project and allow Gradle to sync

# 3. Build the APK
./gradlew assembleDebug

# 4. Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🛠 First-Time Setup

1. Open the app → **Settings**
2. Enable required capabilities (Telegram, AutoPhone, etc.)
3. Add your **Telegram Bot Token**
4. (Optional) Add GitHub/API keys for integrations

---

## 📚 Documentation

* **User Guide** → `docs/user_guide.md`
* **Developer Guide** → `docs/developer_guide.md`
* **API Reference** → `docs/api/`

---

## 🤝 Contributing

We welcome contributions!

1. Fork the repository
2. Create a branch:

```bash
git checkout -b feat/your-feature
```

3. Run tests:

```bash
./gradlew test
```

4. Open a Pull Request to `main`

Make sure your PR passes CI and follows the project structure.

---

## 📜 Code of Conduct

This project follows the **Contributor Covenant**.
See `CODE_OF_CONDUCT.md` for details.

---

## 📄 License

MIT License — see `LICENSE`

---

## 🌍 Community

* Telegram (Forge OS): [https://t.me/+ZCOyKzTFB5k1ZjE0](https://t.me/+ZCOyKzTFB5k1ZjE0)
* Telegram (Forge Labs): *(coming soon)*
* GitHub Discussions: [https://github.com/thekingsmediastudio/forge-os/discussions](https://github.com/thekingsmediastudio/forge-os/discussions)

---

## 💡 Vision

Forge OS isn’t just an app—it’s a **foundation for building AI-powered systems directly on mobile devices**.
