# 🚀 QUICK REFERENCE - SDK VERSION FIX

## Problem
```
INSTALL_FAILED_DEPRECATED_SDK_VERSION: App must target SDK 24+, found 21
```

## Solution
✅ **Automatic SDK patching implemented**

## How to Use

### Build
```bash
mvn clean compile
```

### Run
```bash
mvn exec:java \
  -Dexec.mainClass=mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  -Dexec.args="--apk your-app.apk --db Intent.sqlite --log-seconds 30"
```

## What Happens Automatically

1. ✅ APK is decoded
2. ✅ targetSdkVersion is updated to 24 (if < 24)
3. ✅ Activities are exported
4. ✅ Smali files are instrumented
5. ✅ APK is rebuilt and signed
6. ✅ **Installation succeeds!** ✅
7. ✅ WebView calls are captured
8. ✅ Data is enriched in database

## Expected Output

```
Patching AndroidManifest.xml to update SDK version...
  Updated targetSdkVersion from 21 to 24
✅ AndroidManifest.xml SDK version patched successfully
...
Installation successful ✅
```

## Status
✅ **Ready to use**

---

For detailed information, see: FINAL_SDK_FIX.md

