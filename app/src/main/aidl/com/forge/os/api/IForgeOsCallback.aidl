// Forge OS — External API callback surface.
package com.forge.os.api;

oneway interface IForgeOsCallback {
    /** Streamed text/data chunk (used by askAgent and async tools). */
    void onChunk(String text);
    /** Final result payload as JSON. */
    void onResult(String jsonResult);
    /** Terminal error. [code]: 400 bad args, 403 perm denied, 429 rate limited, 500 internal. */
    void onError(int code, String message);
}
