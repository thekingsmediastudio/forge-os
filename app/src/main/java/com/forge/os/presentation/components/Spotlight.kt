package com.forge.os.presentation.components

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Global registry for spotlight targets.
 * Elements register their bounds here for the tutorial to find them.
 */
object SpotlightRegistry {
    private val _targets = mutableStateMapOf<String, Rect>()
    val targets: Map<String, Rect> get() = _targets

    fun register(key: String, rect: Rect) {
        _targets[key] = rect
    }

    fun unregister(key: String) {
        _targets.remove(key)
    }

    fun getBounds(key: String): Rect? = _targets[key]
}

/**
 * Modifier to register an element as a spotlight target.
 */
fun Modifier.spotlightTarget(key: String): Modifier = this.onGloballyPositioned { coordinates ->
    SpotlightRegistry.register(key, coordinates.boundsInRoot())
}
