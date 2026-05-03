# Phase O — MainActivity.kt route addition

Add the following `composable` block inside the `NavHost` in `MainActivity.kt`,
alongside the existing `companion`, `companionCheckIns`, and `persona` routes:

```kotlin
// Phase O-5 — Companion Memory transparency screen
composable("companionMemory") {
    com.forge.os.presentation.screens.companion.CompanionMemoryScreen(
        onBack = { navController.popBackStack() }
    )
}
```

The route is already wired in `HubScreen.kt` (tile "Companion Memory" → `"companionMemory"`).
No other changes are needed in MainActivity.
