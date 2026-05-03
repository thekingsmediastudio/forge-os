# forge-os-sample-client

Minimal integration test app for the Forge OS External API.

## What it does

1. Declares the `com.forge.os.permission.USE_API` permission.
2. On launch, binds to `com.forge.os.api.IForgeOsService`.
3. Calls `listTools()` and shows the result; lets you tap a tool to invoke it
   with empty `{}` args.
4. Has a text field to call `askAgent(prompt, ...)` and stream the response.

## Files

```
forge-os-sample-client/
  build.gradle                          – stand-alone Android module
  src/main/AndroidManifest.xml          – declares USE_API permission
  src/main/aidl/com/forge/os/api/       – copies of the two .aidl files
  src/main/java/com/example/forgeclient/MainActivity.kt
```

The two AIDL files **must be byte-identical** to the ones in the Forge OS
project so both apps generate the same `IForgeOsService.Stub.asInterface(...)`.
