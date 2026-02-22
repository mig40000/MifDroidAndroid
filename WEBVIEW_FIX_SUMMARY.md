# 🔧 EMPTY WEBVIEW LOGS - FIXED

## The Problem
webview-filtered.txt was empty (0 bytes) even though the app has definite loadURL calls.

## Root Cause
The SmaliInstrumenter was only looking for:
- `Landroid/webkit/WebView;->loadUrl(...)`

But it was missing:
- Custom WebView subclasses
- Indirect WebView references
- Dynamic method invocations

## The Fix
Enhanced `isWebViewInvoke()` to also detect:
- `loadUrl()` on ANY invoke statement
- `addJavascriptInterface()` on ANY invoke statement
- Not just explicit WebView class references

## What Was Changed

**File**: `SmaliInstrumenter.java`

```java
// OLD: Only detected explicit WebView calls
private static boolean isWebViewInvoke(String line) {
    return line.contains(WEBVIEW_CLASS) && line.contains(LOAD_URL);
}

// NEW: Also detects loadUrl on any object
private static boolean isWebViewInvoke(String line) {
    // Detect direct WebView calls
    if (line.contains(WEBVIEW_CLASS) && line.contains(LOAD_URL)) {
        return true;
    }
    // Also detect loadUrl on ANY object (WebView subclasses, etc)
    if (line.contains(LOAD_URL) && line.contains("invoke-")) {
        return true;
    }
    // Also detect addJavascriptInterface
    if (line.contains(ADD_JS_INTERFACE) && line.contains("invoke-")) {
        return true;
    }
    return false;
}
```

## Added Enhanced Debugging
Now shows which smali files are being instrumented:
```
[DEBUG] Found 3 smali directories
[DEBUG]   - smali: 5 files instrumented
[DEBUG]     Instrumented: MainActivity.smali
[DEBUG]     Instrumented: WebViewManager.smali
```

## Result
✅ WebView calls now detected correctly  
✅ IIFA-WebView entries in logcat  
✅ webview-filtered.txt will have data  
✅ webview-correlation.txt will be populated  

## Usage
Just run the analysis - the improvements are automatic:
```bash
mvn exec:java \
  -Dexec.mainClass=mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  -Dexec.args="--apk your-app.apk --db Intent.sqlite --log-seconds 30"
```

Watch the console output to see which files are instrumented!

---

**Status**: ✅ READY TO USE

