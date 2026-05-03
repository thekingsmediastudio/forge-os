package com.forge.os.bridge;

import com.forge.os.bridge.IForgeBridgeCallback;

/**
 * Forge Bridge — universal tool-provider interface.
 *
 * Any Android app that wants to expose tools to the Forge OS agent implements
 * this AIDL interface and exports a Service with the action:
 *
 *   com.forge.os.bridge.TOOL_PROVIDER
 *
 * Forge OS automatically discovers installed bridge apps, binds to them,
 * queries their tool manifests, and makes those tools available in the agent's
 * ToolRegistry — no Forge OS source changes required.
 *
 * ── Bridge lifecycle ────────────────────────────────────────────────────────
 * 1. Forge OS queries PackageManager for all services with the action above.
 * 2. It binds to each service and calls getBridgeInfo() + getToolManifest().
 * 3. Tool definitions are registered in BridgeToolProvider.
 * 4. When the agent calls a bridge tool, Forge OS calls dispatch(name, args).
 * 5. If the bridge app is killed, Forge OS rebinds automatically.
 *
 * ── Tool manifest format ────────────────────────────────────────────────────
 * getToolManifest() must return a JSON array:
 * [
 *   {
 *     "name":        "my_tool_name",       // unique, use app-prefix e.g. "cam_take_photo"
 *     "description": "What this tool does",
 *     "params": {
 *       "param_name": {
 *         "type":        "string",          // string | integer | number | boolean
 *         "description": "What it means",
 *         "required":    true               // optional, defaults to false
 *       }
 *     }
 *   }
 * ]
 *
 * ── Bridge info format ──────────────────────────────────────────────────────
 * getBridgeInfo() must return a JSON object:
 * {
 *   "id":          "com.example.myapp",   // package name
 *   "name":        "My Bridge App",       // display name
 *   "version":     "1.0.0",
 *   "description": "Short description",
 *   "icon_uri":    "content://..."        // optional
 * }
 */
interface IForgeBridgeService {

    /** JSON object — bridge identity (id, name, version, description). */
    String getBridgeInfo();

    /**
     * JSON array — full tool manifest.
     * Called once on bind and whenever the manifest changes.
     * Must be fast (< 100 ms); Forge OS calls this on the binder thread.
     */
    String getToolManifest();

    /**
     * Execute a tool synchronously.
     *
     * @param toolName  Matches a name returned by getToolManifest().
     * @param argsJson  JSON object of argument key/value pairs.
     * @return          Result string — plain text or JSON. Never null.
     *                  On error: return a JSON object {"ok":false,"error":"..."}.
     */
    String dispatch(String toolName, String argsJson);

    /**
     * Register a callback so the bridge can push events back to Forge OS
     * (e.g. "notification arrived", "tool manifest updated").
     * Pass null to unregister.
     */
    void setCallback(IForgeBridgeCallback callback);

    /**
     * Returns true once the bridge is fully initialised and ready to handle
     * dispatch() calls. Forge OS may call this before dispatching.
     */
    boolean isReady();
}
