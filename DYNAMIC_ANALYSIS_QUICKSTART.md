# Dynamic Analysis - Quick Start Guide

## Prerequisites
- APK file (e.g., `apps/au.id.micolous.farebot_3920.apk`)
- SQLite database (e.g., `Database/Intent.sqlite`)
- Android device/emulator connected via adb
- apktool, apksigner, adb, keytool in PATH (or use --path options)

## Basic Usage

```bash
# Clean compile
mvn -q -DskipTests clean compile

# Run dynamic analysis
java -cp target/classes:/path/to/sqlite-jdbc-3.34.0.jar \
  mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk apps/au.id.micolous.farebot_3920.apk \
  --db Database/Intent.sqlite
```

## Output Files
The analysis generates in `output/dynamic/`:
- `webview-logcat.txt` - Raw logcat output (60 seconds of capture)
- `webview-filtered.txt` - Filtered WebView logs only
- `webview-correlation.txt` - Correlated report with bridge details
- `intent-overrides.auto.txt` - Inferred intent extras
- `intent-overrides.txt` - User-provided overrides (if created)
- `instrumented-signed.apk` - Instrumented APK that was installed

## Expected Output Format

### webview-filtered.txt (Runtime)
```
HH:MM:SS.mmm I/IIFA-WebView-loadUrl(PID): [Context: ClassName] URL: <actual-url>
HH:MM:SS.mmm I/IIFA-WebView-addJavascriptInterface(PID): [Context: ClassName] Interface: <name> Bridge: <class>
```

### webview-correlation.txt
```
Context: au.id.micolous.metrodroid.fragment.TripMapFragment
  Interface Object: TripMapShim
  Bridge Class: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
  loadUrl: file:///android_asset/map.html
  Bridge Methods:
    - String getTileUrl()
    - String getSubdomains()
    - Marker getMarker(int)
    - int getMarkerCount()
```

## Customization

### Increase/Decrease Timing
Edit timing constants in `DynamicAnalysisCLI.java`:
- **Line 467**: `logSeconds = Duration.ofSeconds(60);` - Logcat capture duration
- **Line 470**: `activityDelay = Duration.ofSeconds(3);` - Delay between activities
- **Line 540**: Monkey event count (currently 200-300)
- **Line 546**: Monkey throttle in ms (currently 200)

### Target Specific Device
```bash
java -cp ... mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk ... --db ... \
  --device <device-serial>
```

### Use Custom Tools
```bash
java -cp ... mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk ... --db ... \
  --apktool /path/to/apktool \
  --apksigner /path/to/apksigner \
  --adb /path/to/adb
```

## Troubleshooting

### No WebView calls captured
- Ensure activities are actually launching (check console for "OK" status)
- Check if app requires user interaction beyond monkey events
- Increase `--log-seconds` to capture longer windows
- Ensure instrumentation ran (check "Instrumented smali files" count)

### Activities failing to launch
- Check manifest was properly patched (`Patched X activities to be exported`)
- Verify activities exist in manifest
- Check if app needs specific intents or extras

### Empty webview-filtered.txt
- Activities didn't launch successfully
- WebView code wasn't instrumented (check smali file count)
- WebView calls happen in non-instrumented code paths

## Performance Notes
- Total runtime: ~2-3 minutes per APK
  - ~30s: APK decode
  - ~20s: Instrumentation + build
  - ~30s: APK signing
  - ~60s: Runtime capture (activity launch + UI interaction + async wait)
  - ~30s: Logcat parsing and report generation

## Success Indicators
✅ WebView logs with actual URLs/interfaces (not just `-STATIC` variants)
✅ Multiple contexts captured
✅ Bridge methods populated
✅ webview-correlation.txt with complete data

