/**
 * 
 */
package de.potsdam.ManifestParser;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import de.potsdam.ApplicationDetails.ApplicationDetails;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;


/**
 * @author abhishektiwari
 *
 * Parses Android Manifest.xml files to extract application metadata.
 * Handles activities, services, receivers, providers, and their associated intents.
 */
public class ManifestParser {
	
	// XML element names
	private static final String MANIFEST = "manifest";
	private static final String ACTIVITY = "activity";
	private static final String SERVICE = "service";
	private static final String RECEIVER = "receiver";
	private static final String PROVIDER = "provider";
	private static final String ACTION = "action";
	private static final String DATA = "data";
	private static final String USES_PERMISSION = "uses-permission";

	// XML attribute names
	private static final String ANDROID_NAME = "android:name";
	private static final String NAME = "name";
	private static final String PACKAGE = "package";
	private static final String ANDROID_SCHEME = "android:scheme";
	private static final String ANDROID_HOST = "android:host";
	private static final String ANDROID_PORT = "android:port";
	private static final String ANDROID_MIME_TYPE = "android:mimeType";

	// Statistics tracking
	private int activitiesFound = 0;
	private int servicesFound = 0;
	private int receiversFound = 0;
	private int providersFound = 0;
	private int intentsFound = 0;

	/**
	 * Parses the AndroidManifest.xml file and extracts application metadata.
	 *
	 * @param applicationPath Path to the extracted APK directory
	 * @param appDetails ApplicationDetails object to populate with parsed data
	 * @param logger Logger for informational and error messages
	 */
	public void parseManifest(String applicationPath, ApplicationDetails appDetails, Logger logger) {
		
		if (applicationPath == null || appDetails == null || logger == null) {
			System.err.println("Error: null arguments provided to parseManifest");
			return;
		}

		logger.info("Starting manifest parsing");

		// Remove .apk extension if present
		String manifestPath = cleanApplicationPath(applicationPath);
		String fullManifestPath = manifestPath + "/AndroidManifest.xml";

		// Reset statistics
		resetStatistics();

		try (InputStream file = new FileInputStream(fullManifestPath)) {
			logger.info("Parsing manifest from: " + fullManifestPath);
			parseManifestStream(file, appDetails, logger);
			logParsingStatistics(logger);

		} catch (FileNotFoundException e) {
			logger.severe("Manifest file not found: " + fullManifestPath);
		} catch (IOException e) {
			logger.severe("IO error while reading manifest: " + e.getMessage());
		} catch (Exception e) {
			logger.severe("Error parsing manifest: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Parses the manifest XML stream.
	 *
	 * @param file InputStream of the manifest file
	 * @param appDetails ApplicationDetails to populate
	 * @param logger Logger instance
	 * @throws XmlPullParserException if XML parsing fails
	 * @throws IOException if file reading fails
	 */
	private void parseManifestStream(InputStream file, ApplicationDetails appDetails, Logger logger)
			throws XmlPullParserException, IOException {

		XmlPullParserFactory xmlFactory = XmlPullParserFactory.newInstance();
		XmlPullParser parser = xmlFactory.newPullParser();
		parser.setInput(file, null);

		String packageName = null;
		String currentComponent = null;
		List<String> currentIntents = new ArrayList<>();

		int event = parser.getEventType();
		while (event != XmlPullParser.END_DOCUMENT) {
			String tagName = parser.getName();

			switch (event) {
				case XmlPullParser.START_TAG:
					if (tagName != null) {
						if (MANIFEST.equals(tagName)) {
							packageName = parser.getAttributeValue(null, PACKAGE);
							if (packageName != null) {
								appDetails.setPackageName(packageName);
								logger.info("Package Name: " + packageName);
							}
						}
						else if (isComponentTag(tagName)) {
							currentComponent = extractComponentName(parser, appDetails, logger);
							currentIntents.clear();
							trackComponent(tagName);
						}
						else if (ACTION.equals(tagName)) {
							String action = parser.getAttributeValue(null, ANDROID_NAME);
							if (action != null && !action.trim().isEmpty()) {
								currentIntents.add(action);
								intentsFound++;
								logger.fine("Found action: " + action);
							}
						}
						else if (DATA.equals(tagName)) {
							handleDataTag(parser, currentIntents, logger);
						}
						else if (USES_PERMISSION.equals(tagName)) {
							handlePermission(parser, logger);
						}
					}
					break;

				case XmlPullParser.END_TAG:
					if (tagName != null && isComponentTag(tagName)) {
						if (currentComponent != null && !currentIntents.isEmpty()) {
							appDetails.getHmap().put(currentComponent, new ArrayList<>(currentIntents));
							logger.fine("Registered intents for: " + currentComponent);
						}
						currentComponent = null;
						currentIntents.clear();
					}
					break;
			}

			event = parser.next();
		}
	}

	/**
	 * Handles the data tag extraction (scheme, host, port, mime type).
	 *
	 * @param parser XmlPullParser instance
	 * @param intents List to add extracted intents to
	 * @param logger Logger instance
	 */
	private void handleDataTag(XmlPullParser parser, List<String> intents, Logger logger) {
		String scheme = parser.getAttributeValue(null, ANDROID_SCHEME);
		String host = parser.getAttributeValue(null, ANDROID_HOST);
		String port = parser.getAttributeValue(null, ANDROID_PORT);
		String mimeType = parser.getAttributeValue(null, ANDROID_MIME_TYPE);

		// Build URI if scheme and host present
		if (scheme != null && !scheme.trim().isEmpty() && host != null && !host.trim().isEmpty()) {
			StringBuilder uri = new StringBuilder(scheme).append("://").append(host);
			if (port != null && !port.trim().isEmpty()) {
				uri.append(":").append(port);
			}
			intents.add(uri.toString());
			intentsFound++;
			logger.fine("Found URI intent: " + uri);
		}

		// Add MIME type if present
		if (mimeType != null && !mimeType.trim().isEmpty()) {
			intents.add(mimeType);
			intentsFound++;
			logger.fine("Found MIME type: " + mimeType);
		}
	}

	/**
	 * Handles permission extraction.
	 *
	 * @param parser XmlPullParser instance
	 * @param logger Logger instance
	 */
	private void handlePermission(XmlPullParser parser, Logger logger) {
		String permission = parser.getAttributeValue(null, ANDROID_NAME);
		if (permission != null && !permission.trim().isEmpty()) {
			logger.fine("Found permission: " + permission);
		}
	}

	/**
	 * Checks if a tag name represents a component (activity, service, receiver, provider).
	 *
	 * @param tagName The tag name to check
	 * @return true if this is a component tag
	 */
	private boolean isComponentTag(String tagName) {
		return (ACTIVITY.equals(tagName) || SERVICE.equals(tagName) ||
		        RECEIVER.equals(tagName) || PROVIDER.equals(tagName));
	}

	/**
	 * Extracts the component name from the parser.
	 * Tries android:name first, then falls back to name attribute.
	 *
	 * @param parser XmlPullParser instance
	 * @param appDetails ApplicationDetails instance
	 * @param logger Logger instance
	 * @return The simple component name (without package) or null
	 */
	private String extractComponentName(XmlPullParser parser, ApplicationDetails appDetails, Logger logger) {
		String fullName = parser.getAttributeValue(null, ANDROID_NAME);

		if (fullName == null || fullName.trim().isEmpty()) {
			fullName = parser.getAttributeValue(null, NAME);
		}

		if (fullName != null && !fullName.trim().isEmpty()) {
			String simpleName = getSimpleClassName(fullName);

			// Extract and register folder path
			String folderPath = extractFolderPath(fullName);
			if (folderPath != null && !folderPath.isEmpty()) {
				appDetails.getActivityPath().add(folderPath);
			}

			logger.fine("Extracted component: " + simpleName);
			return simpleName;
		}

		return null;
	}
	
	/**
	 * Tracks statistics for different component types.
	 *
	 * @param componentType The type of component (activity, service, receiver, provider)
	 */
	private void trackComponent(String componentType) {
		switch (componentType) {
			case ACTIVITY:
				activitiesFound++;
				break;
			case SERVICE:
				servicesFound++;
				break;
			case RECEIVER:
				receiversFound++;
				break;
			case PROVIDER:
				providersFound++;
				break;
		}
	}

	/**
	 * Cleans the application path by removing .apk extension.
	 *
	 * @param applicationPath The raw application path
	 * @return Cleaned path
	 */
	private String cleanApplicationPath(String applicationPath) {
		if (applicationPath == null) {
			return "";
		}
		return applicationPath.replace(".apk", "").trim();
	}

	/**
	 * Resets statistics counters.
	 */
	private void resetStatistics() {
		activitiesFound = 0;
		servicesFound = 0;
		receiversFound = 0;
		providersFound = 0;
		intentsFound = 0;
	}

	/**
	 * Logs parsing statistics to the logger.
	 *
	 * @param logger Logger instance
	 */
	private void logParsingStatistics(Logger logger) {
		logger.info("Parsing Statistics:");
		logger.info("  Activities: " + activitiesFound);
		logger.info("  Services: " + servicesFound);
		logger.info("  Receivers: " + receiversFound);
		logger.info("  Providers: " + providersFound);
		logger.info("  Intents/Actions: " + intentsFound);
	}

	/**
	 * Extracts the simple class name from a fully qualified name.
	 * E.g., "com.example.MainActivity" -> "MainActivity"
	 *
	 * @param fullyQualifiedName The fully qualified class name
	 * @return The simple class name
	 */
	public String getSimpleClassName(String fullyQualifiedName) {
		if (fullyQualifiedName == null || fullyQualifiedName.trim().isEmpty()) {
			return null;
		}

		String[] parts = fullyQualifiedName.split("\\.");
		if (parts.length > 0) {
			return parts[parts.length - 1];
		}

		return fullyQualifiedName;
	}

	/**
	 * Extracts the package folder path from a fully qualified class name.
	 * E.g., "com.example.MainActivity" -> "com/example/"
	 *
	 * @param fullyQualifiedName The fully qualified class name
	 * @return The folder path or empty string
	 */
	public String extractFolderPath(String fullyQualifiedName) {
		if (fullyQualifiedName == null || fullyQualifiedName.trim().isEmpty()) {
			return "";
		}

		String[] parts = fullyQualifiedName.split("\\.");
		if (parts.length <= 1) {
			return "";
		}

		// Join all parts except the last (class name) with /
		StringBuilder path = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			path.append(parts[i]).append("/");
		}

		return path.toString();
	}
}






