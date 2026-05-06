# Contributing to Forge OS

Thank you for considering contributing to **Forge OS**! 🎉
This guide will help you get started quickly and effectively.

---

## 🚀 How to Contribute

### 1. Fork the Repository

Click the **Fork** button on GitHub to create your own copy of the repository.

---

### 2. Clone Your Fork

```bash
git clone https://github.com/<your-username>/forge-os.git
cd forge-os
```

---

### 3. Create a Branch

```bash
git checkout -b feat/short-description
```

Use clear, descriptive branch names (e.g., `feat/plugin-system`, `fix/login-crash`).

---

### 4. Code Style

* Follow existing **Kotlin, Java, and Python** conventions in the project
* Use Android Studio formatter:
  `Code → Reformat Code`
* Keep line length ≤ 100 characters
* Add **KDoc/Javadoc/comments** for public APIs
* Write clean, readable, and maintainable code

---

### 5. Testing

Before submitting your changes:

```bash
# Run unit tests
./gradlew test
```

```bash
# Run UI tests (if applicable)
./gradlew connectedAndroidTest
```

* Ensure all tests pass
* Add tests for new features where possible

---

### 6. Commit Messages

We use the **Conventional Commits** format:

```
type(scope?): subject

[optional body]
```

#### Examples:

* `feat(plugin): add Markdown renderer`
* `fix(core): resolve null pointer in IntentHandler`
* `docs(readme): update setup instructions`

---

### 7. Open a Pull Request

1. Push your branch to GitHub
2. Open a Pull Request targeting `main`

Make sure your PR:

* Has a **clear and descriptive title**
* Includes a detailed explanation of changes
* Links related issues (e.g., `Closes #123`)
* Passes all CI / GitHub Actions checks

---

### 8. Review Process

* At least one maintainer approval is required
* Address any requested changes
* Once approved, a maintainer will merge your PR

---

## 🐞 Reporting Bugs

Before opening a bug report:

1. Search existing issues to avoid duplicates

If none exists, include:

* Clear and concise title
* Steps to reproduce
* Expected vs. actual behavior
* Device model and Android version
* Logs or screenshots (if possible)

---

## 💡 Feature Requests

When suggesting a feature, include:

* The problem it solves
* Why it’s needed
* A brief idea of how it could work

---

## 🛠 Development Environment

* **Android Studio Flamingo** (or newer)
* **JDK 17**
* **Gradle 8.5+**
* **Chaquopy 12.0+** (for Python support)

---

## 📄 License

By contributing, you agree that your contributions will be licensed under the **MIT License**.

---

## 🙌 Thank You

Your contributions help make Forge OS better for everyone.
We appreciate your time, effort, and creativity! 🚀
