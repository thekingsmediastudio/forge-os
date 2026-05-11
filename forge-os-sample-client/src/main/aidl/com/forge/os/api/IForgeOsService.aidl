// Copy of the Forge OS AIDL — must stay byte-identical.
package com.forge.os.api;

import com.forge.os.api.IForgeOsCallback;

interface IForgeOsService {
    String getApiVersion();
    String listTools();
    String invokeTool(String toolName, String jsonArgs);
    void invokeToolAsync(String toolName, String jsonArgs, IForgeOsCallback cb);
    void askAgent(String prompt, String optsJson, IForgeOsCallback cb);
    String getMemory(String key);
    void putMemory(String key, String value, String tagsCsv);
    String runSkill(String skillId, String jsonArgs);
}
