# WebView Correlation Feature - Summary

## What Changed

The dynamic analysis module now **correlates runtime WebView usage with static analysis data**.

### Key Enhancements

1. **Context Tracking**
   - Each WebView call is tagged with the activity/fragment class name
   - Uses `RuntimeLogger.setContext()` injected at method entry

2. **addJavascriptInterface Logging**
   - Captures interface object name (e.g., "Reddinator")
   - Captures bridge class (e.g., "TabCommentsFragment$WebInterface")
   - Links to static analysis `webview_prime.intefaceObject`

3. **Correlation Report**
   - Groups WebView calls by context (activity/fragment)
   - Shows interface objects from both static DB and runtime
   - Shows URLs loaded and JavaScript executed in each context
   - File: `output/dynamic/webview-correlation.txt`

### Log Format

**Before:**
```
IIFA-WebView-loadUrl: file:///android_asset/index.html
```

**After:**
```
IIFA-WebView-loadUrl: [Context: au.com.example.MainActivity] URL: file:///android_asset/index.html
IIFA-WebView-addJavascriptInterface: [Context: au.com.example.MainActivity] Interface: MyBridge Bridge: au.com.example.BridgeClass
```

## How to Use

Run the same command as before:

```zsh
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  -Dexec.args="--apk /path/to/app.apk --db /path/to/Intent.sqlite"
```

## New Output Files

1. **webview-logcat.txt** - Full logcat with context info
2. **webview-filtered.txt** - WebView calls only
3. **webview-correlation.txt** - **NEW**: Correlation report

## Example Correlation Report

```
========================================
WebView Runtime Correlation Report
========================================

Context: au.com.wallaceit.reddinator.ui.TabCommentsFragment
  Interface Object: Reddinator
  Bridge Class: au.com.wallaceit.reddinator.ui.TabCommentsFragment$WebInterface
  loadUrl: file:///android_asset/comments.html

Context: au.com.wallaceit.reddinator.activity.MainActivity
  Interface Object: Reddinator
  loadUrl: file:///android_asset/index.html
  evaluateJavascript: setData({"user": "test"})
```

## Benefits

- **Answers "which activity loaded which URL?"**
- **Links interface objects to specific activities**
- **Shows which JavaScript is executed where**
- **Correlates static analysis (DB) with runtime behavior**

## Technical Details

### Instrumentation Changes

1. **SmaliInstrumenter.java**
   - Tracks method entry/exit
   - Injects context at first instruction
   - Instruments `addJavascriptInterface` (2 args)

2. **RuntimeLoggerSmali.java**
   - Added `setContext()` and `getContext()`
   - Added `logAddJavascriptInterface()`
   - Enhanced logging with context tags

3. **WebViewCorrelator.java** (NEW)
   - Parses log file
   - Queries `webview_prime` table
   - Generates correlation report

