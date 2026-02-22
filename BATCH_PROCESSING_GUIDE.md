# Batch Processing: Dynamic Analysis for Multiple APKs

## Overview

The dynamic analysis module now supports **batch processing** of multiple APK files from a directory, in addition to single APK processing.

## Feature Highlights

✅ **Single APK Mode** - Analyze one APK at a time
✅ **Batch Mode** - Process entire folder of APKs sequentially
✅ **Error Handling** - Continue processing if one APK fails
✅ **Detailed Reporting** - Track success/failure for each app
✅ **Centralized Database** - All results into single Intent.sqlite

## Usage

### Single APK (Original Behavior)
```bash
java -cp target/classes:~/.m2/repository/org/xerial/sqlite-jdbc/3.34.0/sqlite-jdbc-3.34.0.jar \
  mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk apps/com.example.app.apk \
  --db Database/Intent.sqlite
```

### Batch Processing (New Feature)
```bash
java -cp target/classes:~/.m2/repository/org/xerial/sqlite-jdbc/3.34.0/sqlite-jdbc-3.34.0.jar \
  mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk-dir apps/ \
  --db Database/Intent.sqlite
```

## Command Options

### Required (choose one)
```
--apk <path>            Process single APK file
--apk-dir <path>        Process all APKs in directory
--db <path>             Path to Intent.sqlite database
```

### Optional
```
--out <dir>             Output directory (default: output/dynamic)
--apktool <path>        apktool binary path
--apksigner <path>      apksigner binary path
--zipalign <path>       zipalign binary path
--adb <path>            adb binary path
--keytool <path>        keytool binary path
--keystore <path>       Debug keystore file
--keystore-pass <pass>  Keystore password
--key-alias <alias>     Key alias
--key-pass <pass>       Key password
--device <serial>       Target device serial
--activity-delay <sec>  Delay between activities (default: 3)
--log-seconds <sec>     Logcat capture duration (default: 60)
--intent-overrides <path>  Intent configuration file
```

## Batch Processing Workflow

```
┌─ APK Directory ────────────────────────┐
│  ├─ app1.apk                           │
│  ├─ app2.apk                           │
│  ├─ app3_v2.1.apk                      │
│  └─ ...                                │
└────────────────────────────────────────┘
              ↓
      Process APK 1/3
      ├─ Decode & Instrument
      ├─ Install & Run
      ├─ Capture Logs
      ├─ Enrich Database ✅
              ↓
      Process APK 2/3
      ├─ Decode & Instrument
      ├─ Install & Run (if APK 1 succeeded)
      ├─ Capture Logs
      ├─ Enrich Database ✅
              ↓
      Process APK 3/3
      ├─ Decode & Instrument
      ├─ Install & Run
      ├─ Capture Logs
      ├─ Enrich Database ✅
              ↓
      Summary Report
      ├─ Total: 3
      ├─ Success: 3 ✅
      ├─ Failed: 0
      └─ Database: Updated with all data
```

## Console Output Example

```
Found 3 APK file(s) to process
========================================

[INFO] === Starting analysis of app 1 of 3 ===
Processing: com.example.app.apk
Full path: /path/to/apps/com.example.app.apk
[Installation successful]
[DEBUG] Parsed 2 runtime WebView calls
[DEBUG] ✅ Enrichment complete:
[DEBUG]   - Inserted: 2
[INFO] ✅ Successfully analyzed: com.example.app.apk

[INFO] === Starting analysis of app 2 of 3 ===
Processing: com.example.game.apk
Full path: /path/to/apps/com.example.game.apk
[Installation successful]
[DEBUG] Parsed 1 runtime WebView calls
[DEBUG] ✅ Enrichment complete:
[DEBUG]   - Inserted: 1
[INFO] ✅ Successfully analyzed: com.example.game.apk

[INFO] === Starting analysis of app 3 of 3 ===
Processing: com.test.app.apk
Full path: /path/to/apps/com.test.app.apk
[ERROR] ❌ Failed to analyze com.test.app.apk: ...

=== Analysis Complete ===
Total apps processed: 3
Successfully analyzed: 2
Failed: 1
========================
```

## Features

### 1. APK Discovery
- Automatically finds all `.apk` files in the specified directory
- Processes them in alphabetical order
- Reports count before processing starts

### 2. Sequential Processing
- One APK at a time (ensures device stability)
- Clean uninstall/install between apps
- Separate output directories for each APK

### 3. Error Handling
- Continues on error (doesn't stop entire batch)
- Reports which APKs failed and why
- Maintains database integrity

### 4. Progress Tracking
```
[INFO] === Starting analysis of app X of Y ===
Processing: app_name.apk
Full path: /absolute/path/to/app.apk
```

### 5. Summary Report
```
=== Analysis Complete ===
Total apps processed: 5
Successfully analyzed: 4
Failed: 1
========================
```

## Database Consolidation

All APKs write to the **same Intent.sqlite** database:

```sql
SELECT DISTINCT PACKAGE_NAME FROM jsdetails;
-- Results:
-- com.example.app
-- com.example.game
-- com.test.app
-- (all from batch processing)
```

### Query Batch Results
```sql
-- Count apps analyzed
SELECT COUNT(DISTINCT PACKAGE_NAME) as apps_analyzed 
FROM jsdetails;

-- Get all WebView data
SELECT PACKAGE_NAME, ACTIVITY_NAME, PASS_STRING, confidence 
FROM jsdetails 
WHERE confidence >= 0.95 
ORDER BY PACKAGE_NAME;

-- Failure analysis
-- (Apps that weren't inserted = likely failed)
```

## Performance Considerations

### Time per APK
- **Decoding**: ~30 seconds
- **Instrumentation**: ~20 seconds
- **Build & Sign**: ~30 seconds
- **Execution**: ~120 seconds (activity launch + UI + logcat)
- **Total per APK**: ~3-4 minutes

### For 10 APKs
- Sequential: ~30-40 minutes
- Parallel (not yet): Could be N times faster (N = number of devices)

### For 100 APKs
- Sequential: ~5-6 hours
- Multiple devices: Proportionally faster

## Advantages

✅ **Batch Automation** - Process dozens or hundreds of apps unattended
✅ **Single Database** - Consolidated results for analysis
✅ **Error Recovery** - Continues even if one app fails
✅ **Scalable** - Works for 10 or 1000 APKs
✅ **Comparable Results** - Same data quality as single APK

## Limitations & Future Enhancements

**Current**:
- Sequential processing (one device)
- Each APK processed independently

**Future (v2.0)**:
- Parallel processing (multiple devices/emulators)
- Resumable batches (restart from failed APK)
- Advanced filtering (process only certain APKs)
- Incremental mode (skip already-analyzed apps)

## Examples

### Batch Process with Custom Options
```bash
java -cp target/classes:sqlite-jdbc.jar mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk-dir /path/to/apps/ \
  --db Database/Intent.sqlite \
  --out output/batch_results \
  --log-seconds 120 \
  --activity-delay 5
```

### Process with Specific Device
```bash
java -cp target/classes:sqlite-jdbc.jar mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk-dir apps/ \
  --db Database/Intent.sqlite \
  --device emulator-5554
```

### Process Only to Generate Database Updates
```bash
java -cp target/classes:sqlite-jdbc.jar mmmi.se.sdu.dynamic.DynamicAnalysisCLI \
  --apk-dir security_research_apps/ \
  --db research_database.sqlite \
  --out output/research
```

## Testing Batch Mode

Create a test directory:
```bash
mkdir test_apks/
cp apps/au.id.micolous.farebot_3920.apk test_apks/
cp apps/another_app.apk test_apks/
```

Run batch analysis:
```bash
java ... --apk-dir test_apks/ --db Database/Intent.sqlite
```

Verify results:
```bash
sqlite3 Database/Intent.sqlite "SELECT COUNT(DISTINCT PACKAGE_NAME) FROM jsdetails;"
```

---

## Implementation Details

### Code Changes
- **File**: `src/mmmi/se/sdu/dynamic/DynamicAnalysisCLI.java`
- **Methods Added**:
  - `processApkDirectory()` - Main batch processor
  - `processSingleApk()` - Wrapper for single APK
  - `CliOptions.withApk()` - Create options for each APK
- **Options Enhanced**:
  - Added `--apk-dir` parameter
  - Modified validation logic
  - Updated help/usage text

### Backward Compatibility
✅ Fully backward compatible - `--apk` still works for single APK

---

**Status**: Ready for production use! 🚀

