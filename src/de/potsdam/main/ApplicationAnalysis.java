/**
 * 
 */
package de.potsdam.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.potsdam.ApplicationDetails.ApplicationDetails;
import de.potsdam.evaluateJavascript.EvaluateJavaScriptHandler;
import de.potsdam.Logging.IIFALogger;
import de.potsdam.ManifestParser.ManifestParser;
import de.potsdam.SmaliContent.SmaliContent;
import de.potsdam.bridge.JavascriptInterfaceAnalyzer;
import de.potsdam.constants.GenericConstants;
import de.potsdam.db.LoadUrlDB;
import de.potsdam.extract.ApkToolHandler;
import de.potsdam.extract.CollectClasses;
import de.potsdam.extract.InputApkFileContainer;
import de.potsdam.loadurl.LoadURLAnalyzer;
import de.potsdam.slicer.Slicer;
import jsdownloader.JSDownloader;

/**
 * @author abhishektiwari
 *
 */
public class ApplicationAnalysis {
	
	private InputApkFileContainer fileContainer;
	private ApplicationDetails appDetails;
	private ApkToolHandler apkToolHandler;
	private SmaliContent smaliData;
	private ManifestParser manifestParser;
	private IIFALogger logger;
	private String logDirectory;
	public static int appCounter;
	
	static{
		appCounter = 0;
	}
	
	public ApplicationAnalysis(){
		this.appDetails = new ApplicationDetails();
		this.apkToolHandler = new ApkToolHandler();
		this.smaliData = new SmaliContent();
		this.manifestParser = new ManifestParser();
		this.logger = new IIFALogger();
		this.logDirectory = normalizeLogDirectory(GenericConstants.DEFAULT_LOG_DIRECTORY);
	}
	
	public ApplicationAnalysis(File inputDirectory){
		this();
		this.fileContainer = new InputApkFileContainer(inputDirectory);
	}
	
	public ApplicationAnalysis(File inputDirectory, String logDirectory){
        this();
        this.fileContainer = new InputApkFileContainer(inputDirectory);
        this.setLogDirectory(logDirectory);
    }

	/**
	 * Main analysis method that processes all APK files in the input directory.
	 * Performs comprehensive hybrid application analysis including:
	 * - APK decompilation with APKTool
	 * - Manifest parsing
	 * - JavaScript interface detection
	 * - Smali code analysis
	 * - Backward slicing on loadURL
	 * - Data leak detection
	 */
	public void extractApplicationDetails() {

		LoadUrlDB.initDB();
		
		int totalApps = 0;
		int successfullyAnalyzed = 0;
		int skippedApps = 0;
		int failedApps = 0;

		for (File individualApplication : this.fileContainer.getInputApplicationFiles()) {
			totalApps++;

			try {
				logInfo("=== Starting analysis of app " + totalApps + " ===");

				// Initialize app details
				if (!initializeAppDetails(individualApplication)) {
					skippedApps++;
					continue;
				}

				// Decompile APK with APKTool
				if (!decompileApk()) {
					logWarning("Failed to decompile APK, skipping...");
					skippedApps++;
					this.reInitialize();
					continue;
				}

				// Parse manifest
				parseApplicationManifest();

				// Check if app uses JavaScript interface
				if (!hasJavaScriptInterface()) {
					logInfo("No addJavascriptInterface found, skipping analysis");
					skippedApps++;
					this.reInitialize();
					continue;
				}

				// Collect and parse Smali classes
				if (!collectSmaliClasses()) {
					logWarning("Failed to collect Smali classes, skipping...");
					skippedApps++;
					this.reInitialize();
					continue;
				}

				// Verify JavaScript interface in parsed Smali code
				if (!verifyJavaScriptInterfaceInSmali()) {
					logInfo("No addJavascriptInterface in parsed Smali, skipping...");
					skippedApps++;
					this.reInitialize();
					continue;
				}

				// Perform analysis steps
				performHybridAnalysis();

				// Mark as successfully analyzed
				successfullyAnalyzed++;
				appCounter++;

				logInfo("App " + appCounter + " analyzed successfully");

			} catch (Exception e) {
				failedApps++;
				String appName = individualApplication != null ? individualApplication.getName() : "unknown";
				logSevere("Error analyzing app: " + appName);
				logSevere("Exception: " + e.getMessage());
				e.printStackTrace();
			} finally {
				// Always clean up resources
				this.reInitialize();
			}
		}

		// Post-processing: remove duplicates and download remaining JavaScript
		performPostProcessing();

		// Log final statistics
		logAnalysisStatistics(totalApps, successfullyAnalyzed, skippedApps, failedApps);
	}

	/**
	 * Helper method to log info messages with null check.
	 * Falls back to System.out if logger is not initialized.
	 */
	private void logInfo(String message) {
		if (this.logger != null && this.logger.getLogger() != null) {
			this.logger.getLogger().info(message);
		} else {
			System.out.println("[INFO] " + message);
		}
	}

	/**
	 * Helper method to log warning messages with null check.
	 * Falls back to System.err if logger is not initialized.
	 */
	private void logWarning(String message) {
		if (this.logger != null && this.logger.getLogger() != null) {
			this.logger.getLogger().warning(message);
		} else {
			System.err.println("[WARNING] " + message);
		}
	}

	/**
	 * Helper method to log severe messages with null check.
	 * Falls back to System.err if logger is not initialized.
	 */
	private void logSevere(String message) {
		if (this.logger != null && this.logger.getLogger() != null) {
			this.logger.getLogger().severe(message);
		} else {
			System.err.println("[SEVERE] " + message);
		}
	}

	/**
	 * Initializes application details for the current APK.
	 *
	 * @param apkFile The APK file to analyze
	 * @return true if initialization was successful, false otherwise
	 */
	private boolean initializeAppDetails(File apkFile) {
		try {
			this.appDetails.setAppName(apkFile.toString());
			this.logger.initLogging(this.appDetails.getAppName(), this.logDirectory);
			logInfo("Application name: " + this.appDetails.getAppName());
			this.appDetails.setAppPath(apkFile.getAbsolutePath());
			return true;
		} catch (Exception e) {
			System.err.println("Failed to initialize app details: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Decompiles the APK using APKTool.
	 *
	 * @return true if decompilation was successful, false otherwise
	 */
	private boolean decompileApk() {
		try {
			this.apkToolHandler.dissembeApk(
				this.appDetails.getAppName(),
				this.logger.getLogger(),
				this.appDetails.getAppPath()
			);
			this.appDetails.setSmaliPath(this.appDetails.getAppName());
			return true;
		} catch (Exception e) {
			logSevere("APK decompilation failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Parses the AndroidManifest.xml file.
	 */
	private void parseApplicationManifest() {
		try {
			String manifestPath = GenericConstants.APKTOOL_OUTPUT_DIRECTORY + this.appDetails.getAppName();
			this.manifestParser.parseManifest(manifestPath, this.appDetails, this.logger.getLogger());
		} catch (Exception e) {
			logWarning("Manifest parsing failed: " + e.getMessage());
		}
	}

	/**
	 * Checks if the application uses JavaScript interface by searching for addJavascriptInterface.
	 *
	 * @return true if JavaScript interface is found, false otherwise
	 */
	private boolean hasJavaScriptInterface() {
		String outputDirectory = GenericConstants.APKTOOL_OUTPUT_DIRECTORY + this.appDetails.getAppName();
		int count = checkaddJSInterface(outputDirectory);

		if (count == 0) {
			logInfo("No addJavascriptInterface found in: " + this.appDetails.getAppName());
			return false;
		}

		logInfo("Found " + count + " addJavascriptInterface occurrences");
		return true;
	}

	/**
	 * Collects and parses all Smali class files.
	 *
	 * @return true if collection was successful, false otherwise
	 */
	private boolean collectSmaliClasses() {
		try {
			// Remove duplicate activity paths
			removeDuplicate(this.appDetails.getActivityPath());

			// Build directory path
			String smaliDirectory = GenericConstants.APKTOOL_OUTPUT_DIRECTORY + this.getAppDetails().getSmaliPath();
			smaliDirectory = smaliDirectory.replace("smali/", "");

			logInfo("Collecting Smali classes from: " + smaliDirectory);

			// Collect all Smali files
			CollectClasses.listAllSmali(smaliDirectory, this.smaliData, this.logger.getLogger());

			// Extract bridge bindings and annotated methods
			JavascriptInterfaceAnalyzer.extractAndStore(this);

			return true;
		} catch (Exception e) {
			logSevere("Failed to collect Smali classes: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Verifies that JavaScript interface is present in the parsed Smali code.
	 *
	 * @return true if JavaScript interface is found, false otherwise
	 */
	private boolean verifyJavaScriptInterfaceInSmali() {
		int counter = 0;

		try {
			for (List<String> fileContentInSmaliFormat : this.smaliData.classContent) {
				for (String line : fileContentInSmaliFormat) {
					if (line != null && line.contains(GenericConstants.ADDJSInterface)) {
						counter++;
					}
				}
			}

			if (counter == 0) {
				logInfo("No addJavascriptInterface in parsed Smali code");
				return false;
			}

			logInfo("Verified " + counter + " addJavascriptInterface in Smali code");
			return true;

		} catch (Exception e) {
			logWarning("Error verifying JavaScript interface: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Performs the main hybrid application analysis:
	 * - Backward slicing on loadURL
	 * - LoadURL analysis
	 * - Leak detection
	 */
	private void performHybridAnalysis() {
		// Backward slicing analysis
		performBackwardSlicing();

		// LoadURL analysis
		performLoadUrlAnalysis();

		// Optional: Evaluate JavaScript handler
		// performEvaluateJavaScriptAnalysis();
	}

	/**
	 * Performs backward slicing on loadURL to find potential leaks.
	 */
	private void performBackwardSlicing() {
		try {
			logInfo("Starting backward slicing analysis...");
			new Slicer(this.smaliData.classContent, this.logger.getLogger(), this.appDetails);
			logInfo("Backward slicing completed");
		} catch (Exception e) {
			logSevere("Backward slicing failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Analyzes loadURL calls and extracts JavaScript/URL content.
	 */
	private void performLoadUrlAnalysis() {
		try {
			logInfo("Starting LoadURL analysis...");
			LoadURLAnalyzer.checkLoadUrlType(this);
			logInfo("LoadURL analysis completed");
		} catch (Exception e) {
			logSevere("LoadURL analysis failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Optional: Analyzes evaluateJavaScript calls.
	 */
	@SuppressWarnings("unused")
	private void performEvaluateJavaScriptAnalysis() {
		try {
			logInfo("Starting evaluateJavaScript analysis...");
			EvaluateJavaScriptHandler.checkEvaluateJavaScript(this);
			logInfo("EvaluateJavaScript analysis completed");
		} catch (Exception e) {
			logSevere("EvaluateJavaScript analysis failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Performs post-processing after all apps have been analyzed.
	 * Removes duplicates and downloads remaining JavaScript files.
	 */
	private void performPostProcessing() {
		try {
			System.out.println("Performing post-processing...");
			JSDownloader.removeDuplicates();
			JSDownloader.getAltJSDetails();
			System.out.println("Post-processing completed");
		} catch (Exception e) {
			System.err.println("Post-processing failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Logs comprehensive analysis statistics.
	 *
	 * @param totalApps Total number of apps processed
	 * @param successfullyAnalyzed Number of successfully analyzed apps
	 * @param skippedApps Number of skipped apps
	 * @param failedApps Number of failed apps
	 */
	private void logAnalysisStatistics(int totalApps, int successfullyAnalyzed, int skippedApps, int failedApps) {
		System.out.println("\n=== Analysis Complete ===");
		System.out.println("Total apps processed: " + totalApps);
		System.out.println("Successfully analyzed: " + successfullyAnalyzed);
		System.out.println("Skipped (no JS interface): " + skippedApps);
		System.out.println("Failed (errors): " + failedApps);
		System.out.println("Total apps in counter: " + ApplicationAnalysis.appCounter);
		System.out.println("========================\n");
	}

	/**
	 * Checks if the decompiled APK contains addJavascriptInterface using grep.
	 *
	 * @param destination The directory to search in
	 * @return The number of occurrences found
	 */
	public int checkaddJSInterface(String destination) {
		int counter = 0;
		Process process = null;
		BufferedReader reader = null;
		BufferedReader errReader = null;

		try {
			ProcessBuilder pb = new ProcessBuilder(
				"grep", "-ir", "Landroid/webkit/WebView;->addJavascriptInterface", destination
			);
			process = pb.start();

			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

			String line;
			while ((line = reader.readLine()) != null) {
				counter++;
			}

			// Log any errors
			while ((line = errReader.readLine()) != null) {
				logWarning("grep error: " + line);
			}

			process.waitFor();

		} catch (IOException | InterruptedException e) {
			logSevere("Error checking for JavaScript interface: " + e.getMessage());
			e.printStackTrace();
		} finally {
			// Clean up resources
			try {
				if (reader != null) reader.close();
				if (errReader != null) errReader.close();
				if (process != null) process.destroy();
			} catch (IOException e) {
				// Ignore cleanup errors
			}
		}

		return counter;
	}
	
	public void removeDuplicate(List<String> activity_path){
		
		Set<String> temp = new HashSet<>();
		temp.addAll(activity_path);
		activity_path.clear();
		activity_path.addAll(temp);
	}
	
	public void reInitialize(){
		this.appDetails = new ApplicationDetails();
		this.apkToolHandler = new ApkToolHandler();
		this.smaliData = new SmaliContent();
		this.manifestParser = new ManifestParser();
		this.logger = new IIFALogger();
	}

	/**
	 * @return the fileContainer
	 */
	public InputApkFileContainer getFileContainer() {
		return fileContainer;
	}

	/**
	 * @param fileContainer the fileContainer to set
	 */
	public void setFileContainer(InputApkFileContainer fileContainer) {
		this.fileContainer = fileContainer;
	}

	/**
	 * @return the appDetails
	 */
	public ApplicationDetails getAppDetails() {
		return appDetails;
	}

	/**
	 * @param appDetails the appDetails to set
	 */
	public void setAppDetails(ApplicationDetails appDetails) {
		this.appDetails = appDetails;
	}

	/**
	 * @return the apkToolHandler
	 */
	public ApkToolHandler getApkToolHandler() {
		return apkToolHandler;
	}

	/**
	 * @param apkToolHandler the apkToolHandler to set
	 */
	public void setApkToolHandler(ApkToolHandler apkToolHandler) {
		this.apkToolHandler = apkToolHandler;
	}

	/**
	 * @return the smaliData
	 */
	public SmaliContent getSmaliData() {
		return smaliData;
	}

	/**
	 * @param smaliData the smaliData to set
	 */
	public void setSmaliData(SmaliContent smaliData) {
		this.smaliData = smaliData;
	}

	/**
	 * @return the manifestParser
	 */
	public ManifestParser getManifestParser() {
		return manifestParser;
	}

	/**
	 * @param manifestParser the manifestParser to set
	 */
	public void setManifestParser(ManifestParser manifestParser) {
		this.manifestParser = manifestParser;
	}

	/**
	 * @return the logger
	 */
	public IIFALogger getLogger() {
		return logger;
	}

	/**
	 * @param logger the logger to set
	 */
	public void setLogger(IIFALogger logger) {
		this.logger = logger;
	}

	public void setLogDirectory(String logDirectory) {
        this.logDirectory = normalizeLogDirectory(logDirectory);
    }

	public String getLogDirectory() {
        return logDirectory;
    }

	private static String normalizeLogDirectory(String directory) {
        String candidate = (directory == null || directory.trim().isEmpty())
                ? GenericConstants.DEFAULT_LOG_DIRECTORY
                : directory.trim();
        candidate = candidate.replace("\\", File.separator).replace("/", File.separator);
        if (!candidate.endsWith(File.separator)) {
            candidate = candidate + File.separator;
        }
        return candidate;
    }
}
