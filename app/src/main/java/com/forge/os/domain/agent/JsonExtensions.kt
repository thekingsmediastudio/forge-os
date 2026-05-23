package com.forge.os.domain.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull as kotlinxBooleanOrNull

/**
 * Extension to get boolean value from JsonElement or null if not a boolean
 */
val JsonElement.booleanOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.kotlinxBooleanOrNull
