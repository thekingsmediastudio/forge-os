package com.forge.os.data.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

@Serializable
data class BrowserMacro(
    val id: String,
    val name: String,
    val events: List<MacroEvent>,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class MacroEvent(
    val type: String,
    val selector: String?,
    val value: String?,
    val url: String?
)

// InteractionEvent used for recording browser interactions
data class InteractionEvent(
    val type: String,
    val selector: String? = null,
    val value: String? = null,
    val url: String? = null
)

@Singleton
class MacroManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val macrosFile: File get() = context.filesDir.resolve("workspace/system/browser_macros.json")
    
    private val _macros = mutableListOf<BrowserMacro>()
    val macros: List<BrowserMacro> get() = _macros.toList()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        macrosFile.parentFile?.mkdirs()
        loadMacros()
    }
    
    private fun loadMacros() {
        try {
            if (macrosFile.exists()) {
                val data = macrosFile.readText()
                val parsed = json.decodeFromString<List<BrowserMacro>>(data)
                _macros.clear()
                _macros.addAll(parsed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun saveMacro(name: String, interactionEvents: List<InteractionEvent>) {
        val mappedEvents = interactionEvents.map { 
            MacroEvent(type = it.type, selector = it.selector, value = it.value, url = it.url) 
        }
        val macro = BrowserMacro(
            id = UUID.randomUUID().toString(),
            name = name,
            events = mappedEvents
        )
        _macros.add(macro)
        saveToDisk()
    }
    
    private fun saveToDisk() {
        scope.launch {
            try {
                macrosFile.writeText(json.encodeToString(_macros))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
