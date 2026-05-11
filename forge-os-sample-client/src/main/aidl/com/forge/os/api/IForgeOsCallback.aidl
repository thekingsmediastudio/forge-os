// Copy of the Forge OS AIDL — must stay byte-identical.
package com.forge.os.api;

oneway interface IForgeOsCallback {
    void onChunk(String text);
    void onResult(String jsonResult);
    void onError(int code, String message);
}
