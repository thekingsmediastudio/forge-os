// Forge OS — External API
// Bound service surface for other apps on the device.
// API version: 1.0
package com.forge.os.api;

import com.forge.os.api.IForgeOsCallback;

interface IForgeOsService {
    /** SemVer string of this API surface. */
    String getApiVersion();

    /** JSON array of tool definitions the calling app is allowed to invoke. */
    String listTools();

    /** Synchronous tool invocation. Returns JSON {"ok":bool,"output":string,"error":string?}. */
    String invokeTool(String toolName, String jsonArgs);

    /** Async tool invocation; result is delivered via [cb]. */
    void invokeToolAsync(String toolName, String jsonArgs, IForgeOsCallback cb);

    /** Ask the agent. Streams chunks to [cb.onChunk] then a final [cb.onResult]. */
    void askAgent(String prompt, String optsJson, IForgeOsCallback cb);

    /** Read a value from long-term memory by key. Empty string if not found / not allowed. */
    String getMemory(String key);

    /** Store a value in long-term memory with optional comma-separated tags. */
    void putMemory(String key, String value, String tagsCsv);

    /** Run an installed skill by id. */
    String runSkill(String skillId, String jsonArgs);
}
