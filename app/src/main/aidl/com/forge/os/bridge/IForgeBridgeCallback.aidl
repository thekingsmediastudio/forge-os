package com.forge.os.bridge;

/**
 * Forge Bridge — callback interface implemented by Forge OS.
 *
 * Bridge apps receive this callback via IForgeBridgeService.setCallback().
 * Use it to push real-time events back to the Forge OS agent without polling.
 *
 * ── Event JSON format ────────────────────────────────────────────────────────
 * {
 *   "type":    "notification" | "manifest_changed" | "custom",
 *   "payload": { ... }   // type-specific data
 * }
 *
 * Examples:
 *   {"type":"notification","payload":{"app":"WhatsApp","title":"Alice","body":"Hey!"}}
 *   {"type":"manifest_changed"}   ← Forge OS will re-fetch getToolManifest()
 */
interface IForgeBridgeCallback {

    /**
     * Push an arbitrary event to Forge OS.
     * Forge OS logs it and may route it to the agent's event queue.
     * This is a one-way call — it never blocks the bridge app.
     */
    oneway void onBridgeEvent(String eventJson);

    /**
     * Notify Forge OS that the tool manifest has changed.
     * Forge OS will re-call getToolManifest() to refresh tool definitions.
     */
    oneway void onToolManifestChanged();

    /**
     * Notify Forge OS that this bridge is about to disconnect intentionally.
     * Forge OS will mark it as unavailable rather than scheduling a rebind.
     */
    oneway void onBridgeDisconnecting(String reason);
}
