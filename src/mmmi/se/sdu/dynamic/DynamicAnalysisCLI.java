package mmmi.se.sdu.dynamic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xmlpull.v1.XmlPullParserException;

public final class DynamicAnalysisCLI {
	private static final String DEFAULT_OUT_DIR = "output/dynamic";
	private	static final String DEFAULT_KEYSTORE = "output/dynamic/debug.keystore";
	private static final String DEFAULT_KEYSTORE_PASS = "android";
	private static final String DEFAULT_KEY_ALIAS = "androiddebugkey";
	private static final String DEFAULT_KEY_PASS = "android";

	public static void main(String[] args) {
		CliOptions options;
		try {
			options = CliOptions.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println("Argument error: " + e.getMessage());
			CliOptions.printUsage();
			return;
		}

		try {
			if (options.apkDir != null) {
				// Process multiple APKs from directory
				processApkDirectory(options);
			} else {
				// Process single APK
				processSingleApk(options);
			}
		} catch (Exception e) {
			System.err.println("Dynamic analysis failed: " + e.getMessage());
			e.printStackTrace(System.err);
		}
	}

	private static void processApkDirectory(CliOptions options) throws IOException, InterruptedException, SQLException, XmlPullParserException {
		File apkDir = options.apkDir;
		File[] apkFiles = apkDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".apk"));

		if (apkFiles == null || apkFiles.length == 0) {
			System.out.println("No APK files found in directory: " + apkDir);
			return;
		}

		System.out.println("Found " + apkFiles.length + " APK file(s) to process");
		System.out.println("========================================");

		int successCount = 0;
		int failureCount = 0;

		for (int i = 0; i < apkFiles.length; i++) {
			File apkFile = apkFiles[i];
			System.out.println();
			System.out.println("[INFO] === Starting analysis of app " + (i + 1) + " of " + apkFiles.length + " ===");
			System.out.println("Processing: " + apkFile.getName());
			System.out.println("Full path: " + apkFile.getAbsolutePath());

			try {
				// Create a copy of options with this specific APK
				CliOptions singleOptions = options.withApk(apkFile);
				processSingleApk(singleOptions);
				successCount++;
				System.out.println("[INFO] ✅ Successfully analyzed: " + apkFile.getName());
			} catch (Exception e) {
				failureCount++;
				System.err.println("[ERROR] ❌ Failed to analyze " + apkFile.getName() + ": " + e.getMessage());
				e.printStackTrace(System.err);
			}
		}

		// Print summary
		System.out.println();
		System.out.println("=== Analysis Complete ===");
		System.out.println("Total apps processed: " + apkFiles.length);
		System.out.println("Successfully analyzed: " + successCount);
		System.out.println("Failed: " + failureCount);
		System.out.println("========================");
	}

	private static void processSingleApk(CliOptions options) throws IOException, InterruptedException, SQLException, XmlPullParserException {
		run(options);
	}

	private static void run(CliOptions options) throws IOException, InterruptedException, SQLException, XmlPullParserException {
		CommandRunner runner = new CommandRunner();
		Path outDir = options.outDir.toPath();
		Files.createDirectories(outDir);

		Path decodedDir = outDir.resolve("decoded");
		Path unsignedApk = outDir.resolve("instrumented-unsigned.apk");
		Path alignedApk = outDir.resolve("instrumented-aligned.apk");
		Path signedApk = outDir.resolve("instrumented-signed.apk");

		decodeApk(runner, options.apktoolPath, options.apkPath, decodedDir.toFile());

		// Patch manifest to export all activities and update SDK version
		patchManifestExportActivities(decodedDir.resolve("AndroidManifest.xml"));
		patchManifestSdkVersion(decodedDir.resolve("AndroidManifest.xml"));

		int modifiedFiles = SmaliInstrumenter.instrumentDirectory(decodedDir);
		System.out.println("Instrumented smali files: " + modifiedFiles);

		buildApk(runner, options.apktoolPath, decodedDir.toFile(), unsignedApk.toFile());
		Path toSign = tryZipAlign(runner, options.zipalignPath, unsignedApk.toFile(), alignedApk.toFile()) ? alignedApk : unsignedApk;

		ensureKeystore(runner, options);
		signApk(runner, options, toSign.toFile(), signedApk.toFile());

		AndroidManifestInfo manifestInfo = AndroidManifestInfo.parse(decodedDir.resolve("AndroidManifest.xml").toString());
		System.out.println("Package: " + manifestInfo.packageName);

		installAndRun(runner, options, signedApk.toFile(), manifestInfo, options.sqlitePath, decodedDir, options.intentOverridesPath);
	}

	private static void decodeApk(CommandRunner runner, String apktool, File apk, File outDir) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(apktool);
		command.add("d");
		command.add("-f");
		command.add("-o");
		command.add(outDir.getAbsolutePath());
		command.add(apk.getAbsolutePath());
		CommandRunner.Result result = runner.run(command, null);
		if (result.exitCode != 0) {
			throw new IOException("apktool decode failed: " + result.output);
		}
	}

	private static void patchManifestExportActivities(Path manifestPath) throws IOException {
		System.out.println("Patching AndroidManifest.xml to export all activities...");
		List<String> lines = Files.readAllLines(manifestPath);
		List<String> patched = new ArrayList<>();
		boolean inActivity = false;
		boolean hasExported = false;
		int patchedCount = 0;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();

			if (trimmed.startsWith("<activity")) {
				hasExported = line.contains("android:exported");
				// Single-line tag
				if (trimmed.contains("/>")) {
					if (!hasExported) {
						line = line.replace("/>", " android:exported=\"true\" />");
						patchedCount++;
					} else {
						line = line.replace("android:exported=\"false\"", "android:exported=\"true\"");
						if (line.contains("android:exported=\"true\"")) {
							patchedCount++;
						}
					}
					patched.add(line);
					inActivity = false;
					continue;
				}

				// Start tag with closing on same line
				if (trimmed.contains(">")) {
					if (!hasExported) {
						line = line.replace(">", " android:exported=\"true\" >");
						patchedCount++;
					} else {
						line = line.replace("android:exported=\"false\"", "android:exported=\"true\"");
						if (line.contains("android:exported=\"true\"")) {
							patchedCount++;
						}
					}
					patched.add(line);
					inActivity = false;
					continue;
				}

				// Multi-line start tag
				inActivity = true;
				patched.add(line);
				continue;
			}

			if (inActivity) {
				if (line.contains("android:exported")) {
					hasExported = true;
				}
				if (trimmed.contains(">")) {
					if (!hasExported) {
						line = line.replace(">", " android:exported=\"true\" >");
						patchedCount++;
					} else {
						line = line.replace("android:exported=\"false\"", "android:exported=\"true\"");
						if (line.contains("android:exported=\"true\"")) {
							patchedCount++;
						}
					}
					patched.add(line);
					inActivity = false;
					hasExported = false;
					continue;
				}
			}

			patched.add(line);
		}

		Files.write(manifestPath, patched);
		System.out.println("Patched " + patchedCount + " activities to be exported");
	}

	private static void patchManifestSdkVersion(Path manifestPath) throws IOException {
		System.out.println("Patching AndroidManifest.xml to update SDK version...");
		List<String> lines = Files.readAllLines(manifestPath);
		List<String> patched = new ArrayList<>();
		boolean updated = false;
		int minSdkVersion = 24; // Minimum SDK version required by modern Android

		// First pass: Handle single-line and complete uses-sdk tags
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();

			// Look for uses-sdk tag
			if (trimmed.startsWith("<uses-sdk")) {
				// Check if this is a complete tag (contains closing > or />)
				if (trimmed.contains(">") || trimmed.contains("/>")) {
					if (trimmed.contains("android:targetSdkVersion=\"")) {
						// Extract and update existing targetSdkVersion
						int startIdx = trimmed.indexOf("android:targetSdkVersion=\"") + "android:targetSdkVersion=\"".length();
						int endIdx = trimmed.indexOf("\"", startIdx);
						if (startIdx > 25 && endIdx > startIdx) {
							try {
								String versionStr = trimmed.substring(startIdx, endIdx);
								int currentVersion = Integer.parseInt(versionStr);
								if (currentVersion < minSdkVersion) {
									line = line.replace(
										"android:targetSdkVersion=\"" + versionStr + "\"",
										"android:targetSdkVersion=\"" + minSdkVersion + "\""
									);
									System.out.println("  Updated targetSdkVersion from " + currentVersion + " to " + minSdkVersion);
									updated = true;
								}
							} catch (NumberFormatException e) {
								// Ignore parse errors, continue
							}
						}
					} else if (trimmed.contains("/>")) {
						// Add targetSdkVersion to existing uses-sdk tag (self-closing)
						line = line.replace("/>", " android:targetSdkVersion=\"" + minSdkVersion + "\" />");
						System.out.println("  Added targetSdkVersion=" + minSdkVersion + " (self-closing tag)");
						updated = true;
					} else if (trimmed.endsWith(">")) {
						// Add targetSdkVersion to existing uses-sdk tag (with closing >)
						line = line.replace(">", " android:targetSdkVersion=\"" + minSdkVersion + "\" >");
						System.out.println("  Added targetSdkVersion=" + minSdkVersion);
						updated = true;
					}
				}
			}

			patched.add(line);
		}

		// Write back the updated manifest
		if (updated) {
			Files.write(manifestPath, patched);
			System.out.println("✅ AndroidManifest.xml SDK version patched successfully");
		} else {
			System.out.println("ℹ️  No targetSdkVersion changes needed (already >= " + minSdkVersion + ")");
		}
	}

	private static void buildApk(CommandRunner runner, String apktool, File decodedDir, File outApk) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(apktool);
		command.add("b");
		command.add(decodedDir.getAbsolutePath());
		command.add("-o");
		command.add(outApk.getAbsolutePath());
		CommandRunner.Result result = runner.run(command, null);
		if (result.exitCode != 0) {
			throw new IOException("apktool build failed: " + result.output);
		}
	}

	private static boolean tryZipAlign(CommandRunner runner, String zipalign, File inputApk, File outputApk) throws IOException, InterruptedException {
		if (zipalign == null || zipalign.isEmpty()) {
			return false;
		}
		List<String> command = new ArrayList<>();
		command.add(zipalign);
		command.add("-f");
		command.add("-p");
		command.add("4");
		command.add(inputApk.getAbsolutePath());
		command.add(outputApk.getAbsolutePath());
		CommandRunner.Result result;
		try {
			result = runner.run(command, null);
		} catch (IOException e) {
			System.err.println("zipalign not available, skipping alignment: " + e.getMessage());
			return false;
		}
		return result.exitCode == 0 && outputApk.exists();
	}

	private static void ensureKeystore(CommandRunner runner, CliOptions options) throws IOException, InterruptedException {
		if (options.keystore.exists()) {
			return;
		}
		Files.createDirectories(options.keystore.toPath().getParent());
		List<String> command = new ArrayList<>();
		command.add(options.keytoolPath);
		command.add("-genkeypair");
		command.add("-keystore");
		command.add(options.keystore.getAbsolutePath());
		command.add("-storepass");
		command.add(options.keystorePass);
		command.add("-keypass");
		command.add(options.keyPass);
		command.add("-alias");
		command.add(options.keyAlias);
		command.add("-keyalg");
		command.add("RSA");
		command.add("-keysize");
		command.add("2048");
		command.add("-validity");
		command.add("10000");
		command.add("-dname");
		command.add("CN=IIFA Debug,O=IIFA,C=US");
		CommandRunner.Result result = runner.run(command, null);
		if (result.exitCode != 0) {
			throw new IOException("keytool failed: " + result.output);
		}
	}

	private static void signApk(CommandRunner runner, CliOptions options, File inputApk, File outputApk) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(options.apksignerPath);
		command.add("sign");
		command.add("--ks");
		command.add(options.keystore.getAbsolutePath());
		command.add("--ks-pass");
		command.add("pass:" + options.keystorePass);
		command.add("--key-pass");
		command.add("pass:" + options.keyPass);
		command.add("--out");
		command.add(outputApk.getAbsolutePath());
		command.add(inputApk.getAbsolutePath());
		CommandRunner.Result result = runner.run(command, null);
		if (result.exitCode != 0) {
			throw new IOException("apksigner failed: " + result.output);
		}
	}

	private static void installAndRun(CommandRunner runner, CliOptions options, File apk, AndroidManifestInfo manifestInfo,
								 String sqlitePath, Path decodedDir, Path intentOverridesPath) throws IOException, InterruptedException, SQLException {
		String adb = options.adbPath;
		String serial = options.deviceSerial;
		List<String> adbPrefix = new ArrayList<>();
		adbPrefix.add(adb);
		if (serial != null && !serial.isEmpty()) {
			adbPrefix.add("-s");
			adbPrefix.add(serial);
		}

		System.out.println("Uninstalling existing package: " + manifestInfo.packageName);
		List<String> uninstall = new ArrayList<>(adbPrefix);
		uninstall.add("uninstall");
		uninstall.add(manifestInfo.packageName);
		runner.run(uninstall, null);

		System.out.println("Installing instrumented APK...");
		List<String> install = new ArrayList<>(adbPrefix);
		install.add("install");
		install.add("-r");
		install.add("-g");
		install.add(apk.getAbsolutePath());
		CommandRunner.Result installResult = runner.run(install, null);
		if (installResult.exitCode != 0) {
			throw new IOException("adb install failed: " + installResult.output);
		}
		System.out.println("Installation successful");

		// Prioritize launcher activity first (most likely to work)
		Set<String> activityTargets = new HashSet<>();
		if (manifestInfo.launcherActivity != null) {
			activityTargets.add(manifestInfo.launcherActivity);
			System.out.println("Using launcher activity: " + manifestInfo.launcherActivity);
		}

		// Add activities from database if available
		List<String> allDbClasses = WebViewDbReader.readInitiatingClasses(sqlitePath);
		Set<String> dbActivities = resolveActivities(allDbClasses, manifestInfo.activities);
		Set<String> dbFragments = new HashSet<>(allDbClasses);
		dbFragments.removeAll(dbActivities);

		if (!dbActivities.isEmpty()) {
			activityTargets.addAll(dbActivities);
			System.out.println("Added " + dbActivities.size() + " WebView activities from database");
			for (String act : dbActivities) {
				System.out.println("  Activity: " + act);
			}
		}

		if (!dbFragments.isEmpty()) {
			System.out.println("Found " + dbFragments.size() + " WebView fragments (need UI navigation):");
			for (String frag : dbFragments) {
				System.out.println("  Fragment: " + frag);
			}
		}

		// Try to map fragments to likely host activities via smali references
		if (!dbFragments.isEmpty()) {
			Map<String, Set<String>> fragmentHosts = FragmentHostResolver.findHostActivities(decodedDir, dbFragments, manifestInfo.activities);
			int hostCount = 0;
			for (Map.Entry<String, Set<String>> entry : fragmentHosts.entrySet()) {
				for (String host : entry.getValue()) {
					if (activityTargets.add(host)) {
						hostCount++;
					}
				}
			}
			if (hostCount > 0) {
				System.out.println("Added " + hostCount + " host activities from fragment mapping");
			}
		}

		suggestOverridesTemplate(intentOverridesPath, activityTargets);

		Map<String, List<IntentOverrides.Extra>> inferredExtras = new HashMap<>();
		try {
			inferredExtras = IntentExtrasScanner.infer(decodedDir, activityTargets);
			if (!inferredExtras.isEmpty()) {
				System.out.println("Inferred intent extras for " + inferredExtras.size() + " activities");
				writeAutoOverrides(intentOverridesPath, inferredExtras);
			}
		} catch (IOException e) {
			System.out.println("WARN: Unable to infer intent extras: " + e.getMessage());
		}

		System.out.println("Total activities to launch: " + activityTargets.size());

		// Start logcat capture BEFORE launching activities
		Path logOutput = options.outDir.toPath().resolve("webview-logcat.txt");
		Process logcatProcess = startLogcatCapture(adbPrefix, logOutput);

		System.out.println("Launching activities...");
		boolean anyLaunched = false;
		IntentOverrides overrides = IntentOverrides.load(intentOverridesPath);
		for (String activity : activityTargets) {
			// Try direct launch first
			List<String> start = new ArrayList<>(adbPrefix);
			start.add("shell");
			start.add("am");
			start.add("start");
			start.add("-n");
			start.add(manifestInfo.packageName + "/" + activity);
			appendIntentExtras(start, mergeExtras(overrides.getExtras(activity), inferredExtras.get(activity)));

			System.out.println("[DEBUG] Launching with: " + String.join(" ", start));
			CommandRunner.Result startResult = runner.run(start, null);

			if (startResult.exitCode == 0) {
				System.out.println("Launch " + activity + ": OK");
				anyLaunched = true;
				Thread.sleep(options.activityLaunchDelay.toMillis());
			} else {
				System.out.println("Launch " + activity + ": FAILED (not exported or restricted)");
				System.out.println("[DEBUG] Exit code: " + startResult.exitCode + ", Output: " + startResult.output);
			}
		}

		// If nothing launched and we have a launcher activity, try via launcher intent
		if (!anyLaunched && manifestInfo.launcherActivity != null) {
			System.out.println("Attempting launcher via MAIN intent...");
			List<String> launchMain = new ArrayList<>(adbPrefix);
			launchMain.add("shell");
			launchMain.add("monkey");
			launchMain.add("-p");
			launchMain.add(manifestInfo.packageName);
			launchMain.add("-c");
			launchMain.add("android.intent.category.LAUNCHER");
			launchMain.add("1");
			CommandRunner.Result mainResult = runner.run(launchMain, null);
			System.out.println("Monkey launcher: " + (mainResult.exitCode == 0 ? "OK" : "FAILED"));
			if (mainResult.exitCode == 0) {
				anyLaunched = true;
				Thread.sleep(options.activityLaunchDelay.toMillis());
			}
		}

		if (!anyLaunched) {
			System.err.println("WARNING: No activities launched successfully. Logs may be empty.");
		} else {
			// Give the app time to fully initialize and load resources
			System.out.println("Waiting for app to initialize...");
			Thread.sleep(5000);  // Extended from 2s to 5s to allow WebView to fully load

			// Perform automated UI interactions to trigger WebView usage
			// Use more events if fragments were detected
			int eventCount = dbFragments.isEmpty() ? 200 : 300;  // Increased from 50/150
			System.out.println("Performing automated UI interactions with monkey (" + eventCount + " events)...");
			List<String> monkey = new ArrayList<>(adbPrefix);
			monkey.add("shell");
			monkey.add("monkey");
			monkey.add("-p");
			monkey.add(manifestInfo.packageName);
			monkey.add("--throttle");
			monkey.add("200");  // Slower interactions (was 300) to allow more time for async work
			monkey.add("--pct-touch");
			monkey.add("60");
			monkey.add("--pct-nav");
			monkey.add("30");
			monkey.add("--pct-majornav");
			monkey.add("10");
			monkey.add(String.valueOf(eventCount));

			CommandRunner.Result monkeyResult = runner.run(monkey, null);
			if (monkeyResult.exitCode == 0) {
				System.out.println("Monkey UI interaction completed (" + eventCount + " events)");
			} else {
				System.out.println("Monkey UI interaction had issues, continuing anyway");
			}

			// Extended wait after monkey to allow async callbacks to complete
			System.out.println("Waiting for async WebView callbacks to complete...");
			Thread.sleep(5000);  // Additional 5s wait
		}

		System.out.println("Collecting logs for " + options.logSeconds.getSeconds() + " seconds...");
		Thread.sleep(options.logSeconds.toMillis());

		logcatProcess.destroy();
		logcatProcess.waitFor();
		System.out.println("Log capture complete: " + logOutput);

		// Filter and summarize WebView logs
		Path webviewFiltered = extractWebViewLogs(logOutput);

		// Generate correlation report
		try {
			Path correlationReport = options.outDir.toPath().resolve("webview-correlation.txt");
			WebViewCorrelator.correlateAndReport(webviewFiltered, options.sqlitePath, correlationReport);
		} catch (Exception e) {
			System.out.println("ERROR generating correlation report: " + e.getMessage());
		 e.printStackTrace();
		}

		// Enrich jsdetails table with runtime-captured values
		try {
			String appName = extractAppNameFromApk(options.apkPath.getAbsolutePath());
			DynamicAnalysisEnricher.enrichJsDetails(webviewFiltered, appName);
			DynamicAnalysisEnricher.printEnrichmentSummary(appName);
		} catch (Exception e) {
			System.out.println("ERROR enriching jsdetails table: " + e.getMessage());
		 e.printStackTrace();
		}
	}

	private static Path extractWebViewLogs(Path logOutput) throws IOException {
		Path filteredOutput = logOutput.getParent().resolve("webview-filtered.txt");
		List<String> webviewLogs = new ArrayList<>();
		List<String> allLines = Files.readAllLines(logOutput);

		// Better handling: collect complete IIFA-WebView entries
		StringBuilder currentEntry = new StringBuilder();
		int iifaCount = 0;

		for (int i = 0; i < allLines.size(); i++) {
			String line = allLines.get(i);

			// Start of a new IIFA-WebView entry
			if (line.contains("IIFA-WebView")) {
				// If we were building a previous entry, save it
				if (currentEntry.length() > 0) {
					webviewLogs.add(currentEntry.toString());
					currentEntry = new StringBuilder();
				}

				// Start new entry
				currentEntry.append(line);
				iifaCount++;

				// Check if this is a complete entry (ends properly) or continues
				if (line.trim().endsWith("V") || line.trim().endsWith(")") ||
				    line.contains("] URL:") || line.contains("] JS:") ||
				    line.contains("] Interface:") || line.contains("] Data:")) {
					// Complete entry, add it
					webviewLogs.add(currentEntry.toString());
					currentEntry = new StringBuilder();
					iifaCount--;
				}
			}
			// Continuation of the previous IIFA-WebView entry
			else if (currentEntry.length() > 0 && !line.matches(".*\\d{2}:\\d{2}:\\d{2}\\.\\d+.*")) {
				// This line doesn't start with a timestamp, so it's a continuation
				currentEntry.append("\n").append(line);
			}
		}

		// Don't forget the last entry if it's still being built
		if (currentEntry.length() > 0) {
			webviewLogs.add(currentEntry.toString());
		}

		Files.write(filteredOutput, webviewLogs);
		System.out.println("Filtered WebView logs: " + filteredOutput);
		System.out.println("Total WebView calls captured: " + webviewLogs.size());

		// Count by type
		int loadUrlCount = 0, loadDataCount = 0, evalJsCount = 0, addJsCount = 0;
		for (String log : webviewLogs) {
			if (log.contains("IIFA-WebView-loadUrl")) loadUrlCount++;
			else if (log.contains("IIFA-WebView-loadData")) loadDataCount++;
			else if (log.contains("IIFA-WebView-evaluateJavascript")) evalJsCount++;
			else if (log.contains("IIFA-WebView-addJavascriptInterface")) addJsCount++;
		}

		System.out.println("  - loadUrl calls: " + loadUrlCount);
		System.out.println("  - loadData calls: " + loadDataCount);
		System.out.println("  - evaluateJavascript calls: " + evalJsCount);
		System.out.println("  - addJavascriptInterface calls: " + addJsCount);

		if (webviewLogs.isEmpty()) {
			System.out.println("No WebView calls detected. Possible reasons:");
			System.out.println("  - App didn't use WebView during the capture window");
			System.out.println("  - Activities didn't launch successfully");
			System.out.println("  - WebView usage requires user interaction");
		} else {
			System.out.println("\nSample WebView calls:");
			for (int i = 0; i < Math.min(3, webviewLogs.size()); i++) {
				String sample = webviewLogs.get(i);
				// Truncate long lines for display
				if (sample.length() > 120) {
					System.out.println("  " + sample.substring(0, 120) + "...");
				} else {
					System.out.println("  " + sample);
				}
			}
		}

		return filteredOutput;
	}

	private static Set<String> resolveActivities(List<String> classes, List<String> manifestActivities) {
		Set<String> matched = new HashSet<>();
		for (String className : classes) {
			String match = findActivityMatch(className, manifestActivities);
			if (match != null) {
				matched.add(match);
			}
		}
		return matched;
	}

	private static String findActivityMatch(String className, List<String> activities) {
		if (className == null) {
			return null;
		}
		for (String activity : activities) {
			if (activity.equals(className)) {
				return activity;
			}
		}
		String simple = className.substring(className.lastIndexOf('.') + 1);
		for (String activity : activities) {
			if (activity.endsWith("." + simple)) {
				return activity;
			}
		}
		return null;
	}

	private static Process startLogcatCapture(List<String> adbPrefix, Path output) throws IOException, InterruptedException {
		// Clear existing logs first
		List<String> clear = new ArrayList<>(adbPrefix);
		clear.add("logcat");
		clear.add("-c");
		new CommandRunner().run(clear, null);

		// Start logcat capture (only our WebView tags to avoid missing logs in noise)
		List<String> command = new ArrayList<>(adbPrefix);
		command.add("logcat");
		command.add("-v");
		command.add("time");
		command.add("IIFA-WebView-context:I");
		command.add("IIFA-WebView-loadUrl:I");
		command.add("IIFA-WebView-addJavascriptInterface:I");
		command.add("IIFA-WebView-loadData:I");
		command.add("IIFA-WebView-loadDataWithBaseURL:I");
		command.add("IIFA-WebView-evaluateJavascript:I");
		command.add("IIFA-WebView-postUrl:I");
		command.add("IIFA-WebView-loadUrl-RAW:I");
		command.add("IIFA-WebView-addJavascriptInterface-RAW:I");
		command.add("IIFA-WebView-loadData-RAW:I");
		command.add("IIFA-WebView-evaluateJavascript-RAW:I");
		command.add("IIFA-WebView-loadUrl-STATIC:I");
		command.add("IIFA-WebView-addJavascriptInterface-STATIC:I");
		command.add("*:S");

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectOutput(output.toFile());
		builder.redirectErrorStream(true);
		return builder.start();
	}

	private static final class CliOptions {
		final File apkPath;
		final File apkDir;
		final String sqlitePath;
		final File outDir;
		final String apktoolPath;
		final String apksignerPath;
		final String zipalignPath;
		final String adbPath;
		final String keytoolPath;
		final File keystore;
		final String keystorePass;
		final String keyAlias;
		final String keyPass;
		final String deviceSerial;
		final Duration activityLaunchDelay;
		final Duration logSeconds;
		final Path intentOverridesPath;

		private CliOptions(File apkPath, File apkDir, String sqlitePath, File outDir, String apktoolPath, String apksignerPath,
						   String zipalignPath, String adbPath, String keytoolPath, File keystore, String keystorePass,
						   String keyAlias, String keyPass, String deviceSerial, Duration activityLaunchDelay, Duration logSeconds,
						   Path intentOverridesPath) {
			this.apkPath = apkPath;
			this.apkDir = apkDir;
			this.sqlitePath = sqlitePath;
			this.outDir = outDir;
			this.apktoolPath = apktoolPath;
			this.apksignerPath = apksignerPath;
			this.zipalignPath = zipalignPath;
			this.adbPath = adbPath;
			this.keytoolPath = keytoolPath;
			this.keystore = keystore;
			this.keystorePass = keystorePass;
			this.keyAlias = keyAlias;
			this.keyPass = keyPass;
			this.deviceSerial = deviceSerial;
			this.activityLaunchDelay = activityLaunchDelay;
			this.logSeconds = logSeconds;
			this.intentOverridesPath = intentOverridesPath;
		}

		CliOptions withApk(File newApk) {
			return new CliOptions(newApk, null, sqlitePath, outDir, apktoolPath, apksignerPath,
					zipalignPath, adbPath, keytoolPath, keystore, keystorePass, keyAlias, keyPass,
					deviceSerial, activityLaunchDelay, logSeconds, intentOverridesPath);
		}

		static CliOptions parse(String[] args) {
			File apkPath = null;
			File apkDir = null;
			String sqlitePath = null;
			File outDir = new File(DEFAULT_OUT_DIR);
			String apktool = "apktool";
			String apksigner = "apksigner";
			String zipalign = "zipalign";
			String adb = "adb";
			String keytool = "keytool";
			File keystore = new File(DEFAULT_KEYSTORE);
			String keystorePass = DEFAULT_KEYSTORE_PASS;
			String keyAlias = DEFAULT_KEY_ALIAS;
			String keyPass = DEFAULT_KEY_PASS;
			String deviceSerial = null;
			Duration activityDelay = Duration.ofSeconds(2);
			Duration logSeconds = Duration.ofSeconds(30);
			Path intentOverridesPath = java.nio.file.Paths.get("output/dynamic/intent-overrides.txt");

		String[] safeArgs = args == null ? new String[0] : args;
		for (int i = 0; i < safeArgs.length; i++) {
			String token = safeArgs[i];
			switch (token) {
				case "--apk":
					apkPath = new File(requireValue(safeArgs, ++i, token));
					break;
				case "--apk-dir":
					apkDir = new File(requireValue(safeArgs, ++i, token));
					break;
				case "--db":
					sqlitePath = requireValue(safeArgs, ++i, token);
					break;
				case "--out":
					outDir = new File(requireValue(safeArgs, ++i, token));
					break;
				case "--apktool":
					apktool = requireValue(safeArgs, ++i, token);
					break;
				case "--apksigner":
					apksigner = requireValue(safeArgs, ++i, token);
					break;
				case "--zipalign":
					zipalign = requireValue(safeArgs, ++i, token);
					break;
				case "--adb":
					adb = requireValue(safeArgs, ++i, token);
					break;
				case "--keytool":
					keytool = requireValue(safeArgs, ++i, token);
					break;
				case "--keystore":
					keystore = new File(requireValue(safeArgs, ++i, token));
					break;
				case "--keystore-pass":
					keystorePass = requireValue(safeArgs, ++i, token);
					break;
				case "--key-alias":
					keyAlias = requireValue(safeArgs, ++i, token);
					break;
				case "--key-pass":
					keyPass = requireValue(safeArgs, ++i, token);
					break;
				case "--device":
					deviceSerial = requireValue(safeArgs, ++i, token);
					break;
				case "--activity-delay":
					activityDelay = Duration.ofSeconds(Long.parseLong(requireValue(safeArgs, ++i, token)));
					break;
				case "--log-seconds":
					logSeconds = Duration.ofSeconds(Long.parseLong(requireValue(safeArgs, ++i, token)));
					break;
				case "--intent-overrides":
					intentOverridesPath = java.nio.file.Paths.get(requireValue(safeArgs, ++i, token));
					break;
				case "-h":
				case "--help":
					printUsage();
					throw new IllegalArgumentException("Help requested");
				default:
					throw new IllegalArgumentException("Unknown option: " + token);
			}
		}

		// Override defaults with longer times for async callback capture
		if (logSeconds.equals(Duration.ofSeconds(30))) {
			logSeconds = Duration.ofSeconds(60);  // Capture for 60s to get async callbacks
		}
		if (activityDelay.equals(Duration.ofSeconds(2))) {
			activityDelay = Duration.ofSeconds(3);  // Give 3s between activities
		}

			if ((apkPath == null && apkDir == null) || sqlitePath == null) {
				throw new IllegalArgumentException("Either --apk or --apk-dir (and --db) are required");
			}
			if (apkPath != null && !apkPath.exists()) {
				throw new IllegalArgumentException("APK not found: " + apkPath);
			}
			if (apkDir != null && !apkDir.isDirectory()) {
				throw new IllegalArgumentException("APK directory not found or not a directory: " + apkDir);
			}
			File dbFile = new File(sqlitePath);
			if (!dbFile.exists()) {
				throw new IllegalArgumentException("SQLite DB not found: " + sqlitePath);
			}

			return new CliOptions(apkPath, apkDir, sqlitePath, outDir, apktool, apksigner, zipalign, adb, keytool,
					keystore, keystorePass, keyAlias, keyPass, deviceSerial, activityDelay, logSeconds, intentOverridesPath);
		}

		static void printUsage() {
			System.out.println("Usage: java -cp target/classes:mmmi.se.sdu.dynamic.DynamicAnalysisCLI");
			System.out.println("  Single APK: --apk <path> --db <Intent.sqlite> [options]");
			System.out.println("  Directory:  --apk-dir <path> --db <Intent.sqlite> [options]");
			System.out.println();
			System.out.println("Required:");
			System.out.println("  --apk <path>            Path to single APK file");
			System.out.println("  --apk-dir <path>        Path to directory with APK files");
			System.out.println("  --db <path>             Path to Intent.sqlite database");
			System.out.println();
			System.out.println("Optional:");
			System.out.println("  --out <dir>             Output directory (default: output/dynamic)");
			System.out.println("  --apktool <path>        apktool binary (default: apktool)");
			System.out.println("  --apksigner <path>      apksigner binary (default: apksigner)");
			System.out.println("  --zipalign <path>       zipalign binary (default: zipalign)");
			System.out.println("  --adb <path>            adb binary (default: adb)");
			System.out.println("  --keytool <path>        keytool binary (default: keytool)");
			System.out.println("  --keystore <path>       Debug keystore path");
			System.out.println("  --keystore-pass <pass>  Debug keystore password");
			System.out.println("  --key-alias <alias>     Debug key alias");
			System.out.println("  --key-pass <pass>       Debug key password");
			System.out.println("  --device <serial>       adb device serial");
			System.out.println("  --activity-delay <sec>  Delay between activity launches (default: 3)");
			System.out.println("  --log-seconds <sec>     Duration to capture logcat (default: 60)");
			System.out.println("  --intent-overrides <path>  Intent overrides config");
			System.out.println();
			System.out.println("Examples:");
			System.out.println("  Single APK:");
			System.out.println("    java ... --apk app.apk --db Database/Intent.sqlite");
			System.out.println();
			System.out.println("  Batch Processing:");
			System.out.println("    java ... --apk-dir apps/ --db Database/Intent.sqlite");
		}

	private static String requireValue(String[] args, int index, String token) {
		if (index >= args.length) {
			throw new IllegalArgumentException("Missing value for " + token);
		}
		return args[index];
	}
}

private static String extractAppNameFromApk(String apkPath) {
		// Extract app name from APK file path
		// Format: /path/to/appname.apk or /path/to/appname_version.apk
		File apkFile = new File(apkPath);
		String filename = apkFile.getName();
		// Remove .apk extension
		String nameWithoutExt = filename.replaceAll("\\.apk$", "");
		// Remove version suffix if present (e.g., _1, _62, _v1.0)
		String appName = nameWithoutExt.replaceAll("_\\d+.*$", "");
		return appName.isEmpty() ? nameWithoutExt : appName;
	}

	private static void appendIntentExtras(List<String> command, List<IntentOverrides.Extra> extras) {
		if (extras == null || extras.isEmpty()) {
			return;
		}
		for (IntentOverrides.Extra extra : extras) {
			String type = extra.type;
			String key = extra.key;
			String value = extra.value;

			// Skip template placeholders
			if (key == null || key.equals("<key>") || value == null || value.equals("<value>")) {
				continue;
			}

			switch (type) {
				case "string":
					command.add("--es");
					command.add(key);
					command.add(value);
					break;
				case "int":
					command.add("--ei");
					command.add(key);
					command.add(value);
					break;
				case "long":
					command.add("--el");
					command.add(key);
					command.add(value);
					break;
				case "float":
					command.add("--ef");
					command.add(key);
					command.add(value);
					break;
				case "bool":
					command.add("--ez");
					command.add(key);
					command.add(value);
					break;
				case "uri":
					command.add("--eu");
					command.add(key);
					command.add(value);
					break;
				default:
					// Unknown type, ignore
					break;
			}
		}
	}

	private static void suggestOverridesTemplate(Path intentOverridesPath, Set<String> activities) {
		if (intentOverridesPath == null || activities == null || activities.isEmpty()) {
			return;
		}
		if (Files.exists(intentOverridesPath)) {
			return;
		}
		List<String> lines = new ArrayList<>();
		lines.add("# Auto-generated template from webview_prime initiatingClass activity targets");
		lines.add("# Format: ActivityClass|key|type|value");
		lines.add("# Supported types: string,int,long,float,bool,uri");
		for (String activity : activities) {
			lines.add(activity + "|<key>|string|<value>");
		}
		try {
			Files.createDirectories(intentOverridesPath.getParent());
			Files.write(intentOverridesPath, lines);
			System.out.println("Wrote intent overrides template: " + intentOverridesPath);
		} catch (IOException e) {
			System.out.println("WARN: Unable to write intent overrides template: " + e.getMessage());
		}
	}

	private static List<IntentOverrides.Extra> mergeExtras(List<IntentOverrides.Extra> explicit,
												 List<IntentOverrides.Extra> inferred) {
		if ((explicit == null || explicit.isEmpty()) && (inferred == null || inferred.isEmpty())) {
			return new ArrayList<>();
		}
		Map<String, IntentOverrides.Extra> merged = new HashMap<>();
		if (inferred != null) {
			for (IntentOverrides.Extra extra : inferred) {
				merged.put(extra.key, extra);
			}
		}
		if (explicit != null) {
			for (IntentOverrides.Extra extra : explicit) {
				merged.put(extra.key, extra);
			}
		}
		return new ArrayList<>(merged.values());
	}

	private static void writeAutoOverrides(Path intentOverridesPath, Map<String, List<IntentOverrides.Extra>> inferred) {
		if (intentOverridesPath == null || inferred == null || inferred.isEmpty()) {
			return;
		}
		Path autoPath = intentOverridesPath.getParent().resolve("intent-overrides.auto.txt");
		List<String> lines = new ArrayList<>();
		lines.add("# Auto-inferred extras from smali (best-effort defaults)");
		lines.add("# Format: ActivityClass|key|type|value");
		lines.add("# Supported types: string,int,long,float,bool,uri");
		for (Map.Entry<String, List<IntentOverrides.Extra>> entry : inferred.entrySet()) {
			for (IntentOverrides.Extra extra : entry.getValue()) {
				lines.add(entry.getKey() + "|" + extra.key + "|" + extra.type + "|" + extra.value);
			}
		}
		try {
			Files.write(autoPath, lines);
			System.out.println("Wrote inferred intent overrides: " + autoPath);
		} catch (IOException e) {
			System.out.println("WARN: Unable to write inferred overrides: " + e.getMessage());
		}
	}
}
