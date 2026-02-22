# Dynamic Analysis - Diagnostic Test Run

This command will run the dynamic analysis with detailed diagnostic output to understand:
1. Whether RuntimeLogger.smali is created
2. Whether WebView invoke statements are being instrumented
3. Why only STATIC logs are appearing

## Run this command:

```bash
cd /Users/abti/Documents/LTP/SDU/CodeProject/NewHybridAppAnalysis/HybridAppAnalysis && \
rm -rf output/dynamic && \
java -cp target/classes:~/.m2/repository/org/xerial/sqlite-jdbc/3.34.0/sqlite-jdbc-3.34.0.jar \
  mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk apps/au.id.micolous.farebot_3920.apk \
  --db Database/Intent.sqlite \
  2>&1 | tee diagnostic-run.log
```

## What to look for in output:

### 1. RuntimeLogger Creation (should see):
```
[DEBUG] Found 1 smali directories
[DEBUG] Ensuring RuntimeLogger in: output/dynamic/decoded/smali
[DEBUG] ✅ RuntimeLogger.smali created successfully
```

### 2. WebView Instrumentation (should see something like):
```
[DEBUG] Instrumenting loadUrl call in au.id.micolous.metrodroid.fragment.TripMapFragment with register: p0
[DEBUG] Instrumenting addJavascriptInterface call in au.id.micolous.metrodroid.fragment.TripMapFragment
```

### 3. If you DON'T see those messages:
- RuntimeLogger not being created → no runtime logging possible
- No instrumentation messages → WebView calls not being detected/injected

## After running:
1. Check if webview-filtered.txt has RUNTIME logs (without `-STATIC` suffix)
2. Check for any error messages
3. Share the output so we can see what's happening


