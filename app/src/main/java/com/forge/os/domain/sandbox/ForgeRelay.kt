package com.forge.os.domain.sandbox

import com.forge.os.domain.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgeRelay @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val configRepository: com.forge.os.domain.config.ConfigRepository,
    private val configMutationEngine: com.forge.os.domain.config.ConfigMutationEngine
) {
    companion object {
        @JvmStatic
        var instance: ForgeRelay? = null
    }

    init {
        instance = this
    }

    /**
     * Called from Python via Chaquopy.
     * Executes a Forge OS tool and returns the result string.
     */
    fun callTool(name: String, argsJson: String): String {
        Timber.d("ForgeRelay: callTool($name, $argsJson)")
        return try {
            val result = runBlocking {
                toolRegistry.dispatch(name, argsJson, "python_sdk_${System.currentTimeMillis()}")
            }
            if (result.isError) {
                "Error: ${result.output}"
            } else {
                result.output
            }
        } catch (e: Exception) {
            Timber.e(e, "ForgeRelay: failed to call tool $name")
            "Execution Error: ${e.message}"
        }
    }

    fun readConfig(): String {
        return try {
            val config = configRepository.get()
            kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                com.forge.os.domain.config.ForgeConfig.serializer(),
                config
            )
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
    }

    fun writeConfig(path: String, valueJson: String): Boolean {
        return try {
            runBlocking {
                // We use the mutation engine for safety
                configMutationEngine.mutate(path, valueJson)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "ForgeRelay: failed to write config $path")
            false
        }
    }

    fun getDefinitionsJson(): String {
        return try {
            val tools = toolRegistry.getDefinitions()
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(com.forge.os.data.api.ToolDefinition.serializer()),
                tools
            )
        } catch (e: Exception) {
            "[]"
        }
    }
}
