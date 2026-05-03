package com.forge.os.domain.plugins

import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds two reference plugins on first launch so the user has working
 * examples to study and remix:
 *
 *   - hello_world  : trivial echo + counter (persistent across calls)
 *   - text_tools   : word count, reverse, snake-case conversion
 *
 * Both plugins request zero permissions and use only the Python standard
 * library, so they are safe to run anywhere.
 */
@Singleton
class BuiltInPlugins @Inject constructor(
    private val pluginManager: PluginManager
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun seedIfMissing() {
        val installed = pluginManager.listPlugins().map { it.id }.toSet()
        BUILTINS.forEach { (manifestStr, code) ->
            val manifestObj = json.decodeFromString<PluginManifest>(manifestStr)
            if (manifestObj.id !in installed) {
                pluginManager.install(manifestStr, code, source = "builtin").fold(
                    onSuccess = { Timber.i("BuiltInPlugins: seeded ${it.id}") },
                    onFailure = { Timber.w(it, "BuiltInPlugins: failed to seed ${manifestObj.id}") }
                )
            }
        }
    }

    companion object {
        private val HELLO_MANIFEST = """
            {
              "id": "hello_world",
              "name": "Hello World",
              "version": "1.1.0",
              "author": "Forge",
              "description": "Reference plugin: echoes input and counts invocations. Counter persists across calls.",
              "entrypoint": "main.py",
              "permissions": [],
              "tools": [
                { "name": "hello_say",   "description": "Echo a greeting", "params": { "name": "string:Person to greet" } },
                { "name": "hello_count", "description": "Return current call count (persistent across invocations)", "params": {} }
              ]
            }
        """.trimIndent()

        // The plugin runner re-executes the script on every call, so module-level
        // variables like `_state = {"calls": 0}` would reset to zero on each
        // invocation. We use a small JSON file in the system temp directory for
        // persistence so the counter survives across calls.
        private val HELLO_CODE = """
            |# Forge OS built-in plugin: hello_world v1.1
            |import os, json, tempfile
            |
            |_STATE_FILE = os.path.join(tempfile.gettempdir(), "forge_hello_state.json")
            |
            |def _load():
            |    try:
            |        with open(_STATE_FILE, "r") as f:
            |            return json.load(f)
            |    except Exception:
            |        return {"calls": 0}
            |
            |def _save(state):
            |    with open(_STATE_FILE, "w") as f:
            |        json.dump(state, f)
            |
            |def hello_say(name="world", **_):
            |    state = _load()
            |    state["calls"] += 1
            |    _save(state)
            |    return f"👋 Hello, {name}! (call #{state['calls']})"
            |
            |def hello_count(**_):
            |    state = _load()
            |    return f"Total calls so far: {state['calls']}"
        """.trimMargin()

        private val TEXT_MANIFEST = """
            {
              "id": "text_tools",
              "name": "Text Tools",
              "version": "1.0.0",
              "author": "Forge",
              "description": "Pure-Python text utilities: word count, reverse, snake_case.",
              "entrypoint": "main.py",
              "permissions": [],
              "tools": [
                { "name": "text_word_count", "description": "Count words", "params": { "text": "string:Input text" } },
                { "name": "text_reverse",    "description": "Reverse a string", "params": { "text": "string:Input text" } },
                { "name": "text_snake_case", "description": "Convert to snake_case", "params": { "text": "string:Input text" } }
              ]
            }
        """.trimIndent()

        private val TEXT_CODE = """
            |# Forge OS built-in plugin: text_tools
            |import re
            |
            |def text_word_count(text="", **_):
            |    n = len([w for w in text.split() if w])
            |    return f"{n} words"
            |
            |def text_reverse(text="", **_):
            |    return text[::-1]
            |
            |def text_snake_case(text="", **_):
            |    s = re.sub(r'(?<!^)(?=[A-Z])', '_', text).lower()
            |    s = re.sub(r'[^a-z0-9_]+', '_', s)
            |    return re.sub(r'_+', '_', s).strip('_')
        """.trimMargin()

        val BUILTINS: List<Pair<String, String>> = listOf(
            HELLO_MANIFEST to HELLO_CODE,
            TEXT_MANIFEST  to TEXT_CODE
        )
    }
}
