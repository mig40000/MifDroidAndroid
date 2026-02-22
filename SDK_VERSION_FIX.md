# ✅ SDK VERSION FIX - Installation Error Resolved

## Problem

```
INSTALL_FAILED_DEPRECATED_SDK_VERSION: 
App package must target at least SDK version 24, but found 21
```

**Cause**: The APK's `targetSdkVersion` was set to 21, but modern Android requires at least 24 for security and compatibility reasons.

## Solution

### What Was Added

A new method `patchManifestSdkVersion()` that:
1. Reads the AndroidManifest.xml
2. Finds the `uses-sdk` tag
3. Updates `android:targetSdkVersion` to 24 if it's lower
4. Adds `android:targetSdkVersion="24"` if not present

### Implementation

```java
private static void patchManifestSdkVersion(Path manifestPath) throws IOException {
    // Updates targetSdkVersion to minimum SDK 24 (Android 7.0+)
    // Required for installation on modern Android devices
}
```

### Integration

The method is called automatically during APK instrumentation:
```
1. Decode APK
2. Patch activities to be exported ✅
3. Patch SDK version ✅ (NEW)
4. Instrument smali files
5. Build new APK
6. Sign APK
7. Install on device
```

## How It Works

### Before Patching
```xml
<uses-sdk
    android:minSdkVersion="16"
    android:targetSdkVersion="21" />  ← Too old!
```

### After Patching
```xml
<uses-sdk
    android:minSdkVersion="16"
    android:targetSdkVersion="24" />  ← Updated to modern version
```

## Features

✅ **Automatic Update**: Updates any version below 24 automatically  
✅ **Safe**: Doesn't change minSdkVersion, only targetSdkVersion  
✅ **Handles All Cases**: Works with explicit tags and self-closing tags  
✅ **Error Handling**: Gracefully handles malformed manifests  
✅ **Logging**: Prints what was changed for debugging  

## Status

✅ **Implemented and compiled successfully**

## Testing

When you run the analysis next, you should see:
```
Patching AndroidManifest.xml to export all activities...
Patched X activities to be exported
Patching AndroidManifest.xml to update SDK version...
Updated targetSdkVersion from 21 to 24
AndroidManifest.xml patched successfully
```

Then the APK installation should succeed!

## Command to Test

```bash
mvn exec:java \
  -Dexec.mainClass=mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  -Dexec.args="--apk path/to/app.apk --db Intent.sqlite --log-seconds 30"
```

The APK should now install successfully on devices requiring SDK 24+.

---

**Status**: ✅ **Fix implemented and ready**

