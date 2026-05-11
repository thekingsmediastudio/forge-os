package com.forge.os.domain.plugins

import kotlinx.serialization.Serializable

/**
 * On-disk manifest describing an installed plugin.
 *
 *   workspace/plugins/<id>/manifest.json
 *   workspace/plugins/<id>/<entrypoint>     (Python file)
 *
 * Manifest spec:
 *   {
 *     "id": "weather_v1",
 *     "name": "Weather",
 *     "version": "1.0.0",
 *     "author": "Forge",
 *     "description": "Fetches local weather",
 *     "entrypoint": "main.py",
 *     "permissions": ["network", "memory_read"],
 *     "tools": [
 *       { "name": "weather_get", "description": "...", "params": {"city": "string"} }
 *     ]
 *   }
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "unknown",
    val description: String = "",
    val language: String = "python", // "python" or "javascript"
    val entrypoint: String = "main.py",
    val permissions: List<String> = emptyList(),
    val tools: List<PluginTool> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val source: String = "user",     // "builtin" | "user"
    /** Optional SHA-256 hex of the entrypoint code for tamper detection. */
    val sha256: String = "",
    /** Optional Ed25519 base64 signature over entrypoint bytes. */
    val signature: String = "",
    /** Optional Ed25519 base64 public key used to verify [signature]. */
    val publicKey: String = "",
    /**
     * Optional UI contributions — plugins can add tiles to the Hub, surface
     * Android details screens, etc. See [PluginUiContributions].
     */
    val uiContributions: PluginUiContributions? = null,
    /**
     * Phase Q — declared minimum host plugin API version that this plugin is
     * compatible with. See [PluginCompatibility.HOST_API_VERSION]. Defaults to
     * 1 for legacy plugins that pre-date the API version field.
     */
    val minApiVersion: Int? = 1,
    /**
     * Phase Q — declared maximum host plugin API version. `null` (default)
     * means "no upper bound — assume forward compatible".
     */
    val maxApiVersion: Int? = null,
)

/**
 * Optional UI extensions declared by a plugin manifest.
 *
 *   "uiContributions": {
 *     "hubTiles": [
 *       { "symbol": "☀", "title": "WEATHER", "subtitle": "local forecast",
 *         "toolName": "weather_get", "args": "{\"city\":\"here\"}" }
 *     ],
 *     "androidDetailsEnabled": true
 *   }
 *
 * The Forge Hub renders [hubTiles] alongside the built-in modules. Tapping a
 * tile dispatches [HubTile.toolName] with [HubTile.args] and shows the result
 * via the plugin runner. When [androidDetailsEnabled] is true, the plugin is
 * allowed to read the Android device snapshot (battery, network, storage,
 * volume, etc.) produced by `AndroidController.snapshot()`.
 */
@Serializable
data class PluginUiContributions(
    val hubTiles: List<HubTile> = emptyList(),
    val androidDetailsEnabled: Boolean = false,
)

@Serializable
data class HubTile(
    val symbol: String = "□",
    val title: String,
    val subtitle: String = "",
    val toolName: String,
    val args: String = "{}",
)

@Serializable
data class PluginTool(
    val name: String,
    val description: String = "",
    val params: Map<String, String> = emptyMap()    // paramName → "type:description"
)

/**
 * Result of executing a plugin tool.
 */
data class PluginExecutionResult(
    val pluginId: String,
    val toolName: String,
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val error: String? = null
)

/**
 * Permissions a plugin can request. Granted at install-time per the user's
 * permission profile and enforced before each tool dispatch.
 */
object PluginPermissions {
    const val NETWORK       = "network"        // outbound HTTP via python urllib/requests
    const val MEMORY_READ   = "memory_read"    // read-only access to long-term memory
    const val MEMORY_WRITE  = "memory_write"   // store new memories
    const val FILE_READ     = "file_read"      // read workspace files
    const val FILE_WRITE    = "file_write"     // write workspace files
    const val SHELL         = "shell"          // execute shell commands (dangerous)

    val ALL = setOf(NETWORK, MEMORY_READ, MEMORY_WRITE, FILE_READ, FILE_WRITE, SHELL)
    val DANGEROUS = setOf(SHELL, FILE_WRITE)
}
