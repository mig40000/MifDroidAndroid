# Test: STATIC Log Enrichment

## What Was Fixed

The DynamicAnalysisEnricher now parses **STATIC logs** in addition to RUNTIME logs.

### Before
```
URL_PATTERN = Pattern.compile("URL: (.+?)(?:\\s*$|\\s*[^\\s])");
```
Only matched: `URL: file:///...`
Did NOT match: `URL(static): file:///...`

### After
```
URL_PATTERN = Pattern.compile("URL(?:\\(static\\))?: (.+?)(?:\\s*$|\\s*[^\\s])");
```
Matches both: `URL:` and `URL(static):`

## How to Test

The current webview-filtered.txt contains:
```
02-22 18:43:40.337 I/IIFA-WebView-loadUrl-STATIC(...): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] URL(static): file:///android_asset/map.html
02-22 18:43:40.337 I/IIFA-WebView-addJavascriptInterface-STATIC(...): [Context: au.id.micolous.metrodroid.fragment.TripMapFragment] Interface(static): TripMapShim | Bridge: au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
```

The enricher should now:
1. ✅ Parse the STATIC logs
2. ✅ Extract `file:///android_asset/map.html` from the URL pattern
3. ✅ Extract `TripMapShim` from the Interface pattern
4. ✅ Extract bridge class from the Bridge pattern
5. ✅ Update jsdetails table with these values

## Expected Result

Running the analysis again should show:
```
[DEBUG] Parsed 2 runtime WebView calls
[DEBUG] Runtime calls found:
[DEBUG]   - au.id.micolous.metrodroid.fragment.TripMapFragment|loadUrl: loadUrl -> file:///android_asset/map.html
[DEBUG]   - au.id.micolous.metrodroid.fragment.TripMapFragment|addJavascriptInterface: addJavascriptInterface -> TripMapShim -> au.id.micolous.metrodroid.fragment.TripMapFragment$TripMapShim
```

And jsdetails should be updated with:
```
PASS_STRING: file:///android_asset/map.html
confidence: 0.95
resolution_type: DYNAMIC
```

## Run Test
Just run the dynamic analysis again - the enricher will automatically use the STATIC data now.

