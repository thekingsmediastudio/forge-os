# Forge OS

Forge OS is an open‑source AI platform for Android that enables powerful on‑device automation, plugin development, and integration with Telegram, Composio, and more.

## Table of Contents
- [Features](#features)
- [Quick Start](#quick-start)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Code of Conduct](#code-of-conduct)
- [License](#license)
- [Community](#community)

## Features
- **ReAct‑powered agent** with over 200 built‑in tools (browser, file I/O, system control, etc.)
- **Plugin system** for Python, Java/Kotlin, and JavaScript extensions.
- **Telegram integration** for chat‑based control.
- **Off‑screen headless browser** for web automation (cookies shared with user’s browser).
- **Git‑backed workspace** with snapshots, cron jobs, and alarms.
- **Memory & skill store** for reusable knowledge and scripts.
bash
# 1️⃣ Clone the repository
git clone https://github.com/thekingsmediastudio/forge-os.git
cd forge-os

# 2️⃣ Open in Android Studio (recommended Android Studio Flamingo+)
#    Import the Gradle project and let it sync.

# 3️⃣ Build & run
./gradlew assembleDebug   # builds the debug APK
#   or use Android Studio Run ► app

# 4️⃣ Install on your device
adb install -r app/build/outputs/apk/debug/app-debug.apk

### First‑run setup
1. Open the app → Settings → **Enable required capabilities** (Telegram, AutoPhone, etc.).
2. Add your Telegram bot token (or use the built‑in `@ForgeOS` channel).
3. Optionally import a **GitHub secret** for private repo access via Settings → Custom API Keys.

## Documentation
- **User Guide** – `docs/user_guide.md` (installation, basic commands, UI overview).  
- **Developer Guide** – `docs/developer_guide.md` (plugin creation, custom tools, remote GPU worker).  
- **API Reference** – generated from `forgeos/tools/*.py` (see `docs/api/`).

## Contributing
Please read our full contribution guide in **CONTRIBUTING.md**. In short:

1. Fork the repo.
2. Create a feature branch: `git checkout -b feat/awesome-feature`.
3. Write tests (if applicable) and ensure `./gradlew test` passes.
4. Open a Pull Request targeting `main`.
5. PR must pass CI and have at least one approving review.

## Code of Conduct
We follow the Contributor Covenant. See **CODE_OF_CONDUCT.md** for details.

## License
MIT License – see the `LICENSE` file.

## Community
- **Telegram (Forge OS)**: https://t.me/+ZCOyKzTFB5k1ZjE0  
- **Telegram (Forge Labs)**: https://t.me/forge_labs (create when ready)  
- **GitHub Discussions**: https://github.com/thekingsmediastudio/forge-os/discussions  

We welcome ideas, bug reports, and pull requests! 🚀
