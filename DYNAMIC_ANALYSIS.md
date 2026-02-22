# Dynamic WebView Analysis (Automated)

This module instruments APKs to log WebView activity at runtime (e.g., `loadUrl`, `evaluateJavascript`, `addJavascriptInterface`).
It reads `webview_prime` from `Intent.sqlite`, instruments the APK, installs it, launches relevant activities,
then captures WebView logs via `adb logcat` and **correlates them with static analysis data**.

## Features

- **Activity/Fragment tracking**: Identifies which class is using WebView
- **loadUrl capture**: Logs all URLs loaded in WebView
- **evaluateJavascript capture**: Logs JavaScript code executed
- **addJavascriptInterface capture**: Logs bridge object names and classes
- **Correlation report**: Links activities → loadURLs → interface objects from database

## Prerequisites

- `apktool`
- Android SDK build-tools (`apksigner`, `zipalign`)
- `adb`
- `keytool` (JDK)
- Emulator or device connected (`adb devices`)

## Build

```zsh
cd /Users/abti/Documents/LTP/SDU/CodeProject/NewHybridAppAnalysis/HybridAppAnalysis
mvn -q -DskipTests compile
```

## Run (example)

```zsh
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  -Dexec.args="--apk /path/to/app.apk --db /path/to/Intent.sqlite"
```

## Output Files

- `output/dynamic/instrumented-signed.apk` - Instrumented and signed APK
- `output/dynamic/webview-logcat.txt` - Full logcat (all logs)
- `output/dynamic/webview-filtered.txt` - WebView logs only
- `output/dynamic/webview-correlation.txt` - **Correlation report** linking:
  - Activity/Fragment context
  - Interface objects (from static + runtime)
  - Bridge classes
  - URLs loaded
  - JavaScript executed

## Example Correlation Output

```
Context: au.com.wallaceit.reddinator.ui.TabCommentsFragment
  Interface Object: Reddinator
  Bridge Class: au.com.wallaceit.reddinator.ui.TabCommentsFragment$WebInterface
  loadUrl: file:///android_asset/comments.html
  evaluateJavascript: setData({"comments": [...]})
```

## Notes

- The APK is signed with a debug keystore (generated automatically if missing).
- The tool uninstalls the existing app package before installing the instrumented one.
- WebView logs are tagged with `IIFA-WebView-*` and stored in multiple formats.
- Monkey UI automation is used to navigate to WebView-using fragments.

