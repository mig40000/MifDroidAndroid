# Dynamic Analysis Output - Complete Data Reference

## Overview

After running the fixed dynamic analysis pipeline, you now have comprehensive data about WebView usage in hybrid Android applications.

## Output Files Generated

### 1. `webview-logcat.txt` (Raw Logs)
**Contains**: All logcat output over 60-second capture window
**Format**: Raw Android logcat with timestamps
**Size**: ~1-50 KB per app

```
02-22 18:43:40.337 I/IIFA-WebView-context(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment]
02-22 18:43:40.337 I/IIFA-WebView-loadUrl-STATIC(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] URL(static): file:///android_asset/map.html
02-22 18:43:40.337 I/IIFA-WebView-addJavascriptInterface-STATIC(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] Interface(static): TripMapShim | Bridge: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
```

### 2. `webview-filtered.txt` (Parsed WebView Logs)
**Contains**: Only WebView-related calls
**Format**: Filtered and deduplicated from webview-logcat.txt
**Size**: ~100 bytes - 10 KB per app

```
02-22 18:43:40.337 I/IIFA-WebView-context(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment]
02-22 18:43:40.337 I/IIFA-WebView-loadUrl-STATIC(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] URL(static): file:///android_asset/map.html
02-22 18:43:40.337 I/IIFA-WebView-addJavascriptInterface-STATIC(18486): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] Interface(static): TripMapShim | Bridge: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
```

### 3. `webview-correlation.txt` (Correlated Report)
**Contains**: Unified data from static analysis + runtime logs
**Format**: Human-readable report with bridge details
**Size**: ~500 bytes - 5 KB per app

```
========================================
WebView Runtime Correlation Report
========================================

Total contexts found: 1

----------------------------------------
Context: au.id.micolous.metrodroid.fragment.TripMapFragment
  Interface Object: TripMapShim
  Bridge Class: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
  Bridge Methods (from static analysis):
    - [METHOD 1] getTileUrl()Ljava/lang/String;
    - Readable: String getTileUrl()
    - [METHOD 2] getSubdomains()Ljava/lang/String;
    - Readable: String getSubdomains()
    - [METHOD 3] getMarker(I)Lau/id/micolous/metrodroid/util/Marker;
    - Readable: Marker getMarker(int)
    - [METHOD 4] getMarkerCount()I
    - Readable: int getMarkerCount()
```

### 4. `intent-overrides.txt` (Template for Intent Customization)
**Contains**: Template for providing custom intent extras
**Format**: ActivityClass|key|type|value
**Auto-generated**: Yes, one entry per activity

```
au.id.micolous.metrodroid.activity.MainActivity|<key>|string|<value>
au.id.micolous.metrodroid.activity.TripMapActivity|<key>|string|<value>
```

### 5. `intent-overrides.auto.txt` (Inferred Intent Extras)
**Contains**: Auto-detected intent parameters from smali analysis
**Format**: ActivityClass|key|type|value
**Auto-generated**: Yes, if parameters found

```
au.id.micolous.metrodroid.activity.MainActivity|analysis|string|hybrid
au.id.micolous.metrodroid.activity.TripMapActivity|mapType|int|2
```

### 6. `instrumented-signed.apk`
**Contains**: Modified APK with instrumentation
**Format**: Binary APK file
**Size**: ~5-50 MB (same as original)
**Use**: Installed on device for dynamic analysis

## Database Updates

### jsdetails Table
After enrichment, this table contains additional columns:

```sql
SELECT 
  PACKAGE_NAME,
  ACTIVITY_NAME,
  PASS_STRING,           -- URLs, JavaScript code (NOW ENRICHED! ✨)
  confidence,            -- 0.95 for runtime/STATIC data
  resolution_type,       -- DYNAMIC or STATIC
  source_hint,           -- Type: loadUrl, loadData, evaluateJavascript, addJavascriptInterface
  timestamp              -- When the data was captured
FROM jsdetails 
WHERE PACKAGE_NAME = 'au.id.micolous.farebot';
```

**Example Result:**
```
PACKAGE_NAME          | ACTIVITY_NAME                | PASS_STRING                    | confidence | resolution_type
au.id.micolous.farebot| TripMapFragment             | file:///android_asset/map.html | 0.95      | DYNAMIC
au.id.micolous.farebot| TripMapFragment             | TripMapShim                    | 0.95      | DYNAMIC
```

### webview_prime Table
Contains initiating classes and bridge details:

```
PACKAGE_NAME          | initiatingClass | bridgeClass         | intefaceObject
au.id.micolous.farebot| TripMapFragment | TripMapFragment$... | TripMapShim
```

### webview_new Table
Deduplicates and normalizes webview_prime data:

```
appName        | initiatingClass | bridgeClass | intefaceObject | timestamp
au.id.micolous | TripMapFragment | TripMapF... | TripMapShim     | 1234567890
```

## Data Flow

```
APK File
  ↓
SmaliInstrumenter
  ├─ Injects STATIC logging at method entry
  ├─ Captures: URLs, interfaces, bridge classes
  └─ Creates RuntimeLogger
  ↓
Instrumented APK Installation
  ↓
Device Execution + UI Interaction
  ↓
Logcat Capture (60 seconds)
  │
  ├─ webview-logcat.txt (raw)
  │   ↓
  └─ webview-filtered.txt (parsed)
      ↓
DynamicAnalysisEnricher ✨
  ├─ Parses BOTH STATIC and RUNTIME logs (NEW!)
  ├─ Extracts values: URLs, interfaces, methods
  └─ Updates jsdetails table with high confidence
  ↓
WebViewCorrelator
  └─ Generates webview-correlation.txt report
  ↓
DynamicAnalysisEnricher
  └─ Enriches jsdetails with runtime values
```

## What Each Log Type Means

### IIFA-WebView-context
Logs when a new context (Activity/Fragment) is set

```
[Context: au.id.micolous.metrodroid.fragment.TripMapFragment]
```
→ Means: Analysis is now tracking this context

### IIFA-WebView-loadUrl-STATIC
Logs the URL that WOULD be loaded (from code analysis)

```
URL(static): file:///android_asset/map.html
```
→ Means: This URL is hardcoded in the app

### IIFA-WebView-loadUrl (Runtime - if captured)
Logs the actual URL being loaded during execution

```
URL: https://example.com/data.json
```
→ Means: This URL was actually called during the test

### IIFA-WebView-addJavascriptInterface-STATIC
Logs the JavaScript interface object that WOULD be added

```
Interface(static): TripMapShim | Bridge: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
```
→ Means: This JavaScript object bridges to this Android class

## Interpreting Results

### High Confidence Data (0.95)
- Source: Code-level analysis (STATIC extraction)
- Reliability: Very high for static assets/URLs
- Coverage: All code paths
- Use for: Security analysis, vulnerability detection

### Very High Confidence Data (0.99)
- Source: Actual runtime observation
- Reliability: Highest, but only captured paths
- Coverage: Only executed code paths
- Use for: Behavioral analysis, actual usage patterns

## Use Cases

### 1. Security Analysis
```sql
SELECT DISTINCT PASS_STRING
FROM jsdetails
WHERE PACKAGE_NAME = 'au.id.micolous.farebot'
AND confidence >= 0.95
-- Get all URLs/JS loaded by this app
```

### 2. Data Exfiltration Detection
```sql
SELECT ACTIVITY_NAME, PASS_STRING
FROM jsdetails
WHERE PASS_STRING LIKE 'https://%.com%' 
AND confidence >= 0.95
-- Find all remote endpoints
```

### 3. Bridge Security Audit
```sql
SELECT 
  initiatingClass,
  bridgeClass,
  bridgeMethods
FROM webview_prime
WHERE PACKAGE_NAME = 'au.id.micolous.farebot'
-- Find all JavaScript-Android bridges
```

## Next Steps

The data is now ready for:
1. ✅ **Vulnerability scanning** - Check bridges for security issues
2. ✅ **Data flow analysis** - Trace data from JavaScript to Android
3. ✅ **Behavior profiling** - Understand what each WebView does
4. ✅ **Compliance checking** - Verify endpoints and data handling
5. ✅ **Threat modeling** - Identify potential attack surfaces

All data is in `Database/Intent.sqlite` with high confidence scores!

