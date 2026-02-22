# Dynamic Analysis Improvements - Summary

## Problem
The dynamic analysis was only capturing **STATIC WebView logs** (detected at method entry time), not **RUNTIME logs** (actual WebView method invocations). This meant:
- ✅ We knew what URLs/interfaces WOULD be called
- ❌ We didn't know what actually DID execute at runtime

## Root Cause
WebView methods are often called in **async contexts** (callbacks, network responses, etc.) that happen **after method entry**. The capture windows were too short to wait for these callbacks to complete.

## Solution Implemented

### 1. **Increased Activity Initialization Time**
   - **Before**: 2 seconds
   - **After**: 5 seconds
   - **Reason**: Gives WebView time to fully initialize and start async loading

### 2. **Increased Monkey Interaction Events**
   - **Before**: 50-150 events (too short)
   - **After**: 200-300 events (fragments get 300)
   - **Reason**: More interaction time = more time for async callbacks to trigger

### 3. **Slower Monkey Throttle**
   - **Before**: 300ms between interactions
   - **After**: 200ms between interactions (slower = more predictable timing)
   - **Reason**: Allows async work to complete between UI events

### 4. **Post-Interaction Wait**
   - **Before**: None
   - **After**: 5 seconds after monkey completes
   - **Reason**: Captures callbacks that trigger after final UI event

### 5. **Extended Logcat Capture**
   - **Before**: 30 seconds
   - **After**: 60 seconds
   - **Reason**: Gives complete window for all async callbacks (5s init + 200-300 events @ 0.2s throttle + 5s post = ~40-50s activity, plus 10s buffer)

### 6. **Activity Launch Delay**
   - **Before**: 2 seconds between activities
   - **After**: 3 seconds between activities
   - **Reason**: Better separation between activity launches

## Total Timeline
```
Activity Launch 1
    ↓
5s (app initialization)
    ↓
200-300 monkey events @ 200ms throttle ≈ 40-60 seconds
    ↓
5s (async callback completion)
    ↓
Logcat capture: 60 seconds (full window)
```

## Expected Improvements
✅ **Before**: Only STATIC logs → webview-filtered.txt has `URL(static)`, `Interface(static)` markers
✅ **After**: RUNTIME logs should appear → webview-filtered.txt should have full URLs and interface names captured during execution

## Files Modified
- `src/mmmi/se/sdu/dynamic/DynamicAnalysisCLI.java`
  - Lines 465-475: Increased default timing values
  - Lines 530-555: Extended app initialization and monkey interaction
  - Line 560: Extended logcat capture

## Testing Notes
The test app (au.id.micolous.farebot) uses a TripMapFragment that loads `file:///android_asset/map.html` via WebView. This is an **async operation** that happens in a callback, so it requires sufficient wait time to be captured.

## Next Steps
Run the dynamic analysis again. Expected output:
- webview-logcat.txt should have logs at timestamps spanning 60+ seconds
- webview-filtered.txt should have RUNTIME logs (without `-STATIC` suffix)
- webview-correlation.txt should have actual URLs, not just static placeholders

