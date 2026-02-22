/**
 * 
 */
package mmmi.se.sdu.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import mmmi.se.sdu.constants.GenericConstants;
import mmmi.se.sdu.main.ApplicationAnalysis;


/**
 * @author abhishektiwari
 *
 * Database handler for storing LoadURL and JavaScript bridge analysis results.
 * Provides methods to store and manage JavaScript details extracted from hybrid apps.
 */
public class LoadUrlDB {

	private static final String TABLE_JS_DETAILS = "jsdetails";
	private static final String TABLE_WEBVIEW_PRIME = "webview_prime";
	private static final String TABLE_WEBVIEW_NEW = "webview_new";

	/**
	 * Holds partial information when full resolution fails - Phase 1 Enhancement
	 */
	public static class PartialInfo {
		public String methodName;
		public String className;
		public String parameterType;
		public String sourceHint;        // NETWORK, FILE, CONST, UNKNOWN
		public String hints;             // Comma-separated clues
		public float confidence;

		public PartialInfo() {
			this.hints = "";
			this.confidence = 0.3f;
			this.sourceHint = "UNKNOWN";
		}
	}

	/**
	 * Stores JavaScript/URL details extracted from loadUrl analysis with Phase 1 enhancements.
	 * Overloaded version that accepts confidence scoring and metadata.
	 *
	 * @param appAnalyzer ApplicationAnalysis instance containing app details
	 * @param className The class name where the JavaScript was found
	 * @param rawJsString The raw JavaScript/URL string
	 * @param confidence Confidence level for this result
	 * @param dynamicPatterns Comma-separated list of dynamic patterns found
	 * @param partialInfo Partial resolution information with hints
	 */
	public static void storeIntentDetailsEnhanced(
			ApplicationAnalysis appAnalyzer,
			String className,
			String rawJsString,
			ResolutionConfidence confidence,
			String dynamicPatterns,
			PartialInfo partialInfo) {

		// Validate inputs
		if (appAnalyzer == null) {
			System.err.println("[ERROR] Cannot store intent details: appAnalyzer is null");
			return;
		}

		if (className == null || className.trim().isEmpty()) {
			className = "unknown";
		}

		if (rawJsString == null) {
			rawJsString = "";
		}

		if (confidence == null) {
			confidence = ResolutionConfidence.UNKNOWN;
		}

		if (dynamicPatterns == null) {
			dynamicPatterns = "";
		}

		if (partialInfo == null) {
			partialInfo = new PartialInfo();
		}

		// SQL with all Phase 1 columns
		String sql = "INSERT INTO " + TABLE_JS_DETAILS +
			" (PACKAGE_NAME, ACTIVITY_NAME, PASS_STRING, confidence, resolution_type, dynamic_patterns, partial_hints, source_hint, timestamp) " +
			"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

		Connection conn = null;
		PreparedStatement pstmt = null;

		try {
			conn = getConnection();
			if (conn == null) {
				logError(appAnalyzer, "Failed to get database connection");
				return;
			}

			conn.setAutoCommit(false);
			pstmt = conn.prepareStatement(sql);

			String packageName = appAnalyzer.getAppDetails() != null ?
								 appAnalyzer.getAppDetails().getPackageName() : "unknown";

			pstmt.setString(1, packageName);
			pstmt.setString(2, className);
			pstmt.setString(3, rawJsString);
			pstmt.setFloat(4, confidence.score);
			pstmt.setString(5, confidence.type);
			pstmt.setString(6, dynamicPatterns);
			pstmt.setString(7, partialInfo.hints);
			pstmt.setString(8, partialInfo.sourceHint);
			pstmt.setLong(9, System.currentTimeMillis());

			int rowsAffected = pstmt.executeUpdate();
			conn.commit();

			logInfo(appAnalyzer, "Successfully stored " + rowsAffected + " record(s) in database");

		} catch (SQLException e) {
			logError(appAnalyzer, "SQL error while storing intent details: " + e.getMessage());

			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					logError(appAnalyzer, "Failed to rollback transaction: " + rollbackEx.getMessage());
				}
			}
			e.printStackTrace();

		} finally {
			closeResources(pstmt, conn);
		}
	}

	/**
	 * Stores JavaScript/URL details extracted from loadUrl analysis.
	 * Uses PreparedStatement to prevent SQL injection.
	 *
	 * @param appAnalyzer ApplicationAnalysis instance containing app details
	 * @param className The class name where the JavaScript was found
	 * @param rawJsString The raw JavaScript/URL string
	 */
	public static void storeIntentDetails(ApplicationAnalysis appAnalyzer, String className, String rawJsString) {

		// Validate inputs
		if (appAnalyzer == null) {
			System.err.println("[ERROR] Cannot store intent details: appAnalyzer is null");
			return;
		}

		if (className == null || className.trim().isEmpty()) {
			logWarning(appAnalyzer, "Class name is null or empty, using 'unknown'");
			className = "unknown";
		}

		if (rawJsString == null) {
			logWarning(appAnalyzer, "Raw JS string is null, using empty string");
			rawJsString = "";
		}
		
		logInfo(appAnalyzer, "Storing intent details for class: " + className);

		// SQL with PreparedStatement (prevents SQL injection)
		String sql = "INSERT INTO " + TABLE_JS_DETAILS + " (PACKAGE_NAME, ACTIVITY_NAME, PASS_STRING) VALUES (?, ?, ?)";

		Connection conn = null;
		PreparedStatement pstmt = null;

		try {
			// Get database connection
			conn = getConnection();
			if (conn == null) {
				logError(appAnalyzer, "Failed to get database connection");
				return;
			}

			conn.setAutoCommit(false);
			pstmt = conn.prepareStatement(sql);

			// Set parameters safely (no SQL injection risk)
			String packageName = appAnalyzer.getAppDetails() != null ?
								 appAnalyzer.getAppDetails().getPackageName() : "unknown";

			pstmt.setString(1, packageName);
			pstmt.setString(2, className);
			pstmt.setString(3, rawJsString);

			// Execute update
			int rowsAffected = pstmt.executeUpdate();
			conn.commit();

			logInfo(appAnalyzer, "Successfully stored " + rowsAffected + " record(s) in database");

		} catch (SQLException e) {
			logError(appAnalyzer, "SQL error while storing intent details: " + e.getMessage());

			// Rollback on error
			if (conn != null) {
				try {
					conn.rollback();
					logInfo(appAnalyzer, "Transaction rolled back");
				} catch (SQLException rollbackEx) {
					logError(appAnalyzer, "Failed to rollback transaction: " + rollbackEx.getMessage());
				}
			}
			e.printStackTrace();

		} finally {
			// Clean up resources
			closeResources(pstmt, conn);
		}
	}
	
	/**
	 * Initializes the database by creating tables if they don't exist, then clearing all existing data.
	 * This is typically called at the start of a new analysis session.
	 */
	public static void initDB() {

		System.out.println("Initializing database - creating tables and clearing existing data...");

		Connection conn = null;

		try {
			conn = getConnection();
			if (conn == null) {
				System.err.println("[ERROR] Failed to get database connection for initialization");
				return;
			}

			conn.setAutoCommit(false);

			// Create tables if they don't exist
			createTablesIfNotExist(conn);

			// Clear jsdetails table
			clearTable(conn, TABLE_JS_DETAILS);

			// Clear webview_prime table
			clearTable(conn, TABLE_WEBVIEW_PRIME);

			// Clear webview_new table
			clearTable(conn, TABLE_WEBVIEW_NEW);

			conn.commit();
			System.out.println("Database initialized successfully - all tables cleared");

			// Optionally check and upgrade schema
			checkAndUpgradeSchema(conn);

		} catch (SQLException e) {
			System.err.println("[ERROR] Failed to initialize database: " + e.getMessage());

			// Rollback on error
			if (conn != null) {
				try {
					conn.rollback();
					System.out.println("Transaction rolled back");
				} catch (SQLException rollbackEx) {
					System.err.println("[ERROR] Failed to rollback: " + rollbackEx.getMessage());
				}
			}
			e.printStackTrace();

		} finally {
			// Clean up connection
			closeConnection(conn);
		}
	}

	/**
	 * Creates database tables if they don't already exist.
	 * Sets up the schema for storing JavaScript analysis results.
	 *
	 * @param conn Active database connection
	 */
	private static void createTablesIfNotExist(Connection conn) {
		if (conn == null) {
			return;
		}

		try {
			System.out.println("Creating tables if they don't exist...");

			// Create jsdetails table for raw JavaScript strings
			String createJsDetailsTable = "CREATE TABLE IF NOT EXISTS " + TABLE_JS_DETAILS + " ("
					+ "id INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "PACKAGE_NAME TEXT, "
					+ "ACTIVITY_NAME TEXT, "
					+ "PASS_STRING TEXT, "
					+ "confidence FLOAT DEFAULT 0.5, "
					+ "resolution_type TEXT DEFAULT 'UNKNOWN', "
					+ "dynamic_patterns TEXT, "
					+ "partial_hints TEXT, "
					+ "source_hint TEXT, "
					+ "timestamp LONG"
					+ ")";

			// Create webview_prime table with enhanced schema
			String createWebviewPrimeTable = "CREATE TABLE IF NOT EXISTS " + TABLE_WEBVIEW_PRIME + " ("
					+ "id INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "appName TEXT, "
					+ "initiatingClass TEXT, "
					+ "bridgeClass TEXT, "
					+ "intefaceObject TEXT, "
					+ "bridgeMethods TEXT, "
					+ "initiatingMethod TEXT, "
					+ "methodCount INTEGER, "
					+ "timestamp INTEGER, "
					+ "readableInitiatingClass TEXT, "
					+ "readableBridgeClass TEXT"
					+ ")";

			// Create webview_new table with same schema as webview_prime
			String createWebviewNewTable = "CREATE TABLE IF NOT EXISTS " + TABLE_WEBVIEW_NEW + " ("
					+ "id INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "appName TEXT, "
					+ "initiatingClass TEXT, "
					+ "bridgeClass TEXT, "
					+ "intefaceObject TEXT, "
					+ "bridgeMethods TEXT, "
					+ "initiatingMethod TEXT, "
					+ "methodCount INTEGER, "
					+ "timestamp INTEGER, "
					+ "readableInitiatingClass TEXT, "
					+ "readableBridgeClass TEXT"
					+ ")";

			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate(createJsDetailsTable);
				System.out.println("Created or verified jsdetails table");

				stmt.executeUpdate(createWebviewPrimeTable);
				System.out.println("Created or verified webview_prime table");

				stmt.executeUpdate(createWebviewNewTable);
				System.out.println("Created or verified webview_new table");
			}

		} catch (SQLException e) {
			System.out.println("Error creating tables: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Checks if the database schema needs upgrading and applies migrations if needed.
	 * This is optional and won't fail if columns already exist or can't be added.
	 * Ensures both webview_prime and webview_new have matching schemas.
	 *
	 * @param conn Active database connection
	 */
	private static void checkAndUpgradeSchema(Connection conn) {
		if (conn == null) {
			return;
		}

		try {
			System.out.println("Checking database schema...");

			// Try to add new columns if they don't exist
			// Note: SQLite doesn't have IF NOT EXISTS for ALTER TABLE, so we try and catch

			// Upgrade webview_prime table
			String[] newColumnsPrime = {
				"ALTER TABLE " + TABLE_WEBVIEW_PRIME + " ADD COLUMN methodCount INTEGER",
				"ALTER TABLE " + TABLE_WEBVIEW_PRIME + " ADD COLUMN timestamp INTEGER",
				"ALTER TABLE " + TABLE_WEBVIEW_PRIME + " ADD COLUMN readableInitiatingClass TEXT",
				"ALTER TABLE " + TABLE_WEBVIEW_PRIME + " ADD COLUMN readableBridgeClass TEXT"
			};

			// Upgrade webview_new table (must match webview_prime schema)
			String[] newColumnsNew = {
				"ALTER TABLE " + TABLE_WEBVIEW_NEW + " ADD COLUMN methodCount INTEGER",
				"ALTER TABLE " + TABLE_WEBVIEW_NEW + " ADD COLUMN timestamp INTEGER",
				"ALTER TABLE " + TABLE_WEBVIEW_NEW + " ADD COLUMN readableInitiatingClass TEXT",
				"ALTER TABLE " + TABLE_WEBVIEW_NEW + " ADD COLUMN readableBridgeClass TEXT"
			};

			// Upgrade jsdetails table with Phase 1 columns
			String[] newColumnsJsDetails = {
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN confidence FLOAT DEFAULT 0.5",
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN resolution_type TEXT DEFAULT 'UNKNOWN'",
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN dynamic_patterns TEXT",
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN partial_hints TEXT",
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN source_hint TEXT",
				"ALTER TABLE " + TABLE_JS_DETAILS + " ADD COLUMN timestamp LONG"
			};

			conn.setAutoCommit(true);  // Each ALTER TABLE is independent

			// Upgrade webview_prime
			for (String alterSql : newColumnsPrime) {
				try (Statement stmt = conn.createStatement()) {
					stmt.executeUpdate(alterSql);
					System.out.println("Added new column to webview_prime");
				} catch (SQLException e) {
					// Column likely already exists or table doesn't exist - not an error
					// Silently continue
				}
			}

			// Upgrade webview_new (must match webview_prime)
			for (String alterSql : newColumnsNew) {
				try (Statement stmt = conn.createStatement()) {
					stmt.executeUpdate(alterSql);
					System.out.println("Added new column to webview_new");
				} catch (SQLException e) {
					// Column likely already exists or table doesn't exist - not an error
					// Silently continue
				}
			}

			// Upgrade jsdetails (Phase 1 columns)
			for (String alterSql : newColumnsJsDetails) {
				try (Statement stmt = conn.createStatement()) {
					stmt.executeUpdate(alterSql);
					System.out.println("Added new column to jsdetails");
				} catch (SQLException e) {
					// Column likely already exists - not an error
				}
			}

			System.out.println("Schema check complete - both tables have matching schemas");

		} catch (Exception e) {
			// Schema upgrade is optional, don't fail the entire initialization
			System.out.println("Note: Schema upgrade skipped (this is normal for existing databases)");
		}
	}

	/**
	 * Clears all data from a specific table.
	 *
	 * @param conn Active database connection
	 * @param tableName Name of the table to clear
	 * @throws SQLException if deletion fails
	 */
	private static void clearTable(Connection conn, String tableName) throws SQLException {
		String sql = "DELETE FROM " + tableName;

		try (Statement stmt = conn.createStatement()) {
			int rowsDeleted = stmt.executeUpdate(sql);
			System.out.println("Cleared " + rowsDeleted + " rows from table: " + tableName);
		}
	}

	/**
	 * Checks if the enhanced schema (10 columns) is available in the database.
	 *
	 * @param conn Database connection
	 * @return true if enhanced schema exists, false otherwise
	 */
	private static boolean hasEnhancedSchema(Connection conn) {
		try (Statement stmt = conn.createStatement()) {
			// Try to select from a column that only exists in enhanced schema
			stmt.executeQuery("SELECT methodCount FROM " + TABLE_WEBVIEW_PRIME + " LIMIT 1");
			return true;
		} catch (SQLException e) {
			// Column doesn't exist, so enhanced schema is not available
			return false;
		}
	}

	/**
	 * Stores JavaScript bridge details including bridge class, methods, and interface object.
	 * Enhanced format with better structure and separate method storage.
	 * Automatically detects schema and uses appropriate column set.
	 *
	 * @param appName Application name (clean format)
	 * @param initiatingClass Class that creates the WebView (full Smali path)
	 * @param bridgeClass Class that implements the JavaScript bridge (full Smali path)
	 * @param interfaceObject JavaScript interface object name (as accessed from JS)
	 * @param bridgeMethods Comma-separated list of bridge methods (Smali format)
	 * @param initiatingMethod Method where addJavascriptInterface is called (Smali format)
	 * @throws SQLException if database operation fails
	 */
	public static void storeBridgeDetails(String appName,
										 String initiatingClass,
										 String bridgeClass,
										 String interfaceObject,
										 String bridgeMethods,
										 String initiatingMethod) throws SQLException {

		// Validate inputs
		if (appName == null || appName.trim().isEmpty()) {
			System.err.println("[WARNING] App name is null or empty, using 'unknown'");
			appName = "unknown";
		}

		// Clean and format app name (remove path, keep only name)
		String cleanAppName = cleanAppName(appName);

		// Convert Smali class names to readable format
		String readableInitiatingClass = smaliToReadable(initiatingClass);
		String readableBridgeClass = smaliToReadable(bridgeClass);

		// Parse and count methods
		String[] methods = bridgeMethods != null ? bridgeMethods.split("\n") : new String[0];
		int methodCount = methods.length;

		// Format methods with better structure
		String formattedMethods = formatBridgeMethods(methods);

		// Get current timestamp
		long timestamp = System.currentTimeMillis();

		Connection conn = null;
		PreparedStatement pstmt = null;

		try {
			conn = getConnection();
			if (conn == null) {
				throw new SQLException("Failed to get database connection");
			}

			conn.setAutoCommit(false);

			// Check which schema is available
			boolean useEnhancedSchema = hasEnhancedSchema(conn);
			System.out.println("[DEBUG] Schema detection: " + (useEnhancedSchema ? "ENHANCED (10 columns)" : "BASIC (6 columns)"));

			if (useEnhancedSchema) {
				// Use enhanced schema (10 columns)
				String sqlEnhanced = "INSERT INTO " + TABLE_WEBVIEW_PRIME +
						 " (appName, initiatingClass, bridgeClass, intefaceObject, bridgeMethods, " +
						 "initiatingMethod, methodCount, timestamp, readableInitiatingClass, readableBridgeClass) " +
						 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

				pstmt = conn.prepareStatement(sqlEnhanced);

				// Set all 10 parameters
				pstmt.setString(1, cleanAppName);
				pstmt.setString(2, initiatingClass != null ? initiatingClass : "");
				pstmt.setString(3, bridgeClass != null ? bridgeClass : "");
				pstmt.setString(4, interfaceObject != null ? interfaceObject : "");
				pstmt.setString(5, formattedMethods);
				pstmt.setString(6, initiatingMethod != null ? initiatingMethod : "");
				pstmt.setInt(7, methodCount);
				pstmt.setLong(8, timestamp);
				pstmt.setString(9, readableInitiatingClass);
				pstmt.setString(10, readableBridgeClass);

				System.out.println("[DEBUG] Inserting with 10 columns:");
				System.out.println("  [1] appName: " + cleanAppName);
				System.out.println("  [7] methodCount: " + methodCount);
				System.out.println("  [8] timestamp: " + timestamp);
				System.out.println("  [9] readableInitiatingClass: " + readableInitiatingClass);
				System.out.println("  [10] readableBridgeClass: " + readableBridgeClass);

				int rowsAffected = pstmt.executeUpdate();
				conn.commit();

				System.out.println("✅ Successfully stored bridge details (ENHANCED): " + rowsAffected + " record(s)");
				System.out.println("  App: " + cleanAppName);
				System.out.println("  Bridge Class: " + readableBridgeClass);
				System.out.println("  Interface Object: " + interfaceObject);
				System.out.println("  Methods: " + methodCount);
				System.out.println("  Timestamp: " + timestamp);

			} else {
				// Use basic schema (6 columns)
				String sqlBasic = "INSERT INTO " + TABLE_WEBVIEW_PRIME +
						 " (appName, initiatingClass, bridgeClass, intefaceObject, bridgeMethods, initiatingMethod) " +
						 "VALUES (?, ?, ?, ?, ?, ?)";

				pstmt = conn.prepareStatement(sqlBasic);

				// Set 6 parameters
				pstmt.setString(1, cleanAppName);
				pstmt.setString(2, initiatingClass != null ? initiatingClass : "");
				pstmt.setString(3, bridgeClass != null ? bridgeClass : "");
				pstmt.setString(4, interfaceObject != null ? interfaceObject : "");
				pstmt.setString(5, formattedMethods);
				pstmt.setString(6, initiatingMethod != null ? initiatingMethod : "");

				System.out.println("[DEBUG] Inserting with 6 columns (enhanced schema not available)");

				int rowsAffected = pstmt.executeUpdate();
				conn.commit();

				System.out.println("✅ Successfully stored bridge details (BASIC): " + rowsAffected + " record(s)");
				System.out.println("  App: " + cleanAppName);
				System.out.println("  Bridge Class: " + readableBridgeClass);
				System.out.println("  Interface Object: " + interfaceObject);
				System.out.println("  Methods: " + methodCount);
				System.out.println("  Note: Enhanced columns (methodCount, timestamp, etc.) not available in schema");
			}

		} catch (SQLException e) {
			System.err.println("[ERROR] Failed to store bridge details: " + e.getMessage());
			e.printStackTrace();

			// Rollback on error
			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					System.err.println("[ERROR] Rollback failed: " + rollbackEx.getMessage());
				}
			}
			throw e; // Re-throw to notify caller

		} finally {
			closeResources(pstmt, conn);
		}
	}

	/**
	 * Cleans app name by removing path and keeping only the base name.
	 * Example: "/path/to/apps/airofit.apk" -> "airofit"
	 */
	private static String cleanAppName(String appName) {
		if (appName == null) {
			return "unknown";
		}

		// Remove .apk extension
		String cleaned = appName.replace(".apk", "");

		// Get just the filename (remove path)
		int lastSlash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
		if (lastSlash >= 0 && lastSlash < cleaned.length() - 1) {
			cleaned = cleaned.substring(lastSlash + 1);
		}

		return cleaned;
	}

	/**
	 * Converts Smali class format to readable Java format.
	 * Example: "Lcom/google/android/gms/internal/ads/zzajj;" -> "com.google.android.gms.internal.ads.zzajj"
	 */
	private static String smaliToReadable(String smaliClass) {
		if (smaliClass == null || smaliClass.isEmpty()) {
			return "";
		}

		// Remove L prefix and ; suffix
		String readable = smaliClass;
		if (readable.startsWith("L")) {
			readable = readable.substring(1);
		}
		if (readable.endsWith(";")) {
			readable = readable.substring(0, readable.length() - 1);
		}

		// Replace / with .
		readable = readable.replace('/', '.');

		return readable;
	}

	/**
	 * Formats bridge methods with better structure and readability.
	 * Each method is formatted with clear delimiters and includes:
	 * - Method signature
	 * - Return type
	 * - Parameters
	 *
	 * Example output:
	 * [METHOD 1] notify(Ljava/lang/String;)V
	 * [METHOD 2] <init>(Landroid/content/Context;Lcom/google/android/gms/internal/ads/zzbai;)V
	 */
	private static String formatBridgeMethods(String[] methods) {
		if (methods == null || methods.length == 0) {
			return "";
		}

		StringBuilder formatted = new StringBuilder();
		int methodNum = 1;

		for (String method : methods) {
			if (method == null || method.trim().isEmpty()) {
				continue;
			}

			String trimmed = method.trim();

			// Extract method details
			if (trimmed.startsWith(".method")) {
				// Parse method signature
				String signature = parseMethodSignature(trimmed);
				formatted.append("[METHOD ").append(methodNum).append("] ");
				formatted.append(signature);
				formatted.append("\n");

				// Add readable format
				String readableSignature = formatReadableMethod(signature);
				formatted.append("  Readable: ").append(readableSignature);
				formatted.append("\n");

				methodNum++;
			} else {
				// For constructor or simple method format
				formatted.append("[METHOD ").append(methodNum).append("] ");
				formatted.append(trimmed);
				formatted.append("\n");
				methodNum++;
			}
		}

		return formatted.toString();
	}

	/**
	 * Parses a Smali method signature and extracts the clean method name.
	 * Example: ".method public final notify(Ljava/lang/String;)V" -> "notify(Ljava/lang/String;)V"
	 */
	private static String parseMethodSignature(String methodLine) {
		// Remove .method prefix and access modifiers
		String signature = methodLine.replace(".method", "").trim();

		// Remove access modifiers (public, private, protected, static, final, etc.)
		signature = signature.replaceAll("\\b(public|private|protected|static|final|synchronized|abstract|native)\\b", "").trim();

		return signature;
	}

	/**
	 * Formats a method signature into a more readable format.
	 * Example: "notify(Ljava/lang/String;)V" -> "void notify(String)"
	 */
	private static String formatReadableMethod(String signature) {
		if (signature == null || signature.isEmpty()) {
			return "";
		}

		// Extract method name and parameters
		int parenIndex = signature.indexOf('(');
		if (parenIndex < 0) {
			return signature;
		}

		String methodName = signature.substring(0, parenIndex);

		// Extract parameters
		int endParenIndex = signature.indexOf(')');
		if (endParenIndex < 0) {
			return signature;
		}

		String params = signature.substring(parenIndex + 1, endParenIndex);
		String returnType = signature.substring(endParenIndex + 1);

		// Convert return type
		String readableReturn = convertSmaliType(returnType);

		// Convert parameters
		String readableParams = convertParameters(params);

		return readableReturn + " " + methodName + "(" + readableParams + ")";
	}

	/**
	 * Converts Smali type to readable Java type.
	 */
	private static String convertSmaliType(String smaliType) {
		if (smaliType == null || smaliType.isEmpty()) {
			return "";
		}

		switch (smaliType) {
			case "V": return "void";
			case "Z": return "boolean";
			case "B": return "byte";
			case "S": return "short";
			case "C": return "char";
			case "I": return "int";
			case "J": return "long";
			case "F": return "float";
			case "D": return "double";
			default:
				if (smaliType.startsWith("L") && smaliType.endsWith(";")) {
					String className = smaliType.substring(1, smaliType.length() - 1);
					className = className.replace('/', '.');
					// Get simple name
					int lastDot = className.lastIndexOf('.');
					if (lastDot >= 0) {
						className = className.substring(lastDot + 1);
					}
					return className;
				}
				return smaliType;
		}
	}

	/**
	 * Converts Smali parameters to readable format.
	 */
	private static String convertParameters(String params) {
		if (params == null || params.isEmpty()) {
			return "";
		}

		StringBuilder readable = new StringBuilder();
		int i = 0;
		int paramCount = 0;

		while (i < params.length()) {
			char c = params.charAt(i);

			if (paramCount > 0) {
				readable.append(", ");
			}

			if (c == 'L') {
				// Object type
				int semicolon = params.indexOf(';', i);
				if (semicolon > 0) {
					String type = params.substring(i, semicolon + 1);
					readable.append(convertSmaliType(type));
					i = semicolon + 1;
					paramCount++;
				} else {
					i++;
				}
			} else if (c == '[') {
				// Array type
				i++;
				if (i < params.length()) {
					char nextChar = params.charAt(i);
					if (nextChar == 'L') {
						int semicolon = params.indexOf(';', i);
						if (semicolon > 0) {
							String type = params.substring(i, semicolon + 1);
							readable.append(convertSmaliType(type)).append("[]");
							i = semicolon + 1;
						}
					} else {
						readable.append(convertSmaliType(String.valueOf(nextChar))).append("[]");
						i++;
					}
					paramCount++;
				}
			} else {
				// Primitive type
				readable.append(convertSmaliType(String.valueOf(c)));
				i++;
				paramCount++;
			}
		}

		return readable.toString();
	}

	/**
	 * Creates and returns a database connection.
	 *
	 * @return Database connection or null if connection fails
	 */
	private static Connection getConnection() {
		try {
			Class.forName("org.sqlite.JDBC");
			return DriverManager.getConnection(GenericConstants.DB_NAME);
		} catch (ClassNotFoundException e) {
			System.err.println("[ERROR] SQLite JDBC driver not found: " + e.getMessage());
			return null;
		} catch (SQLException e) {
			System.err.println("[ERROR] Failed to connect to database: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Safely closes PreparedStatement and Connection resources.
	 *
	 * @param pstmt PreparedStatement to close
	 * @param conn Connection to close
	 */
	private static void closeResources(PreparedStatement pstmt, Connection conn) {
		if (pstmt != null) {
			try {
				pstmt.close();
			} catch (SQLException e) {
				System.err.println("[WARNING] Failed to close PreparedStatement: " + e.getMessage());
			}
		}
		closeConnection(conn);
	}

	/**
	 * Safely closes a database connection.
	 *
	 * @param conn Connection to close
	 */
	private static void closeConnection(Connection conn) {
		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException e) {
				System.err.println("[WARNING] Failed to close connection: " + e.getMessage());
			}
		}
	}

	/**
	 * Helper method to log info messages.
	 *
	 * @param appAnalyzer ApplicationAnalysis instance
	 * @param message Log message
	 */
	private static void logInfo(ApplicationAnalysis appAnalyzer, String message) {
		if (appAnalyzer != null && appAnalyzer.getLogger() != null && appAnalyzer.getLogger().getLogger() != null) {
			appAnalyzer.getLogger().getLogger().info(message);
		} else {
			System.out.println("[INFO] " + message);
		}
	}

	/**
	 * Helper method to log warning messages.
	 *
	 * @param appAnalyzer ApplicationAnalysis instance
	 * @param message Log message
	 */
	private static void logWarning(ApplicationAnalysis appAnalyzer, String message) {
		if (appAnalyzer != null && appAnalyzer.getLogger() != null && appAnalyzer.getLogger().getLogger() != null) {
			appAnalyzer.getLogger().getLogger().warning(message);
		} else {
			System.err.println("[WARNING] " + message);
		}
	}

	/**
	 * Helper method to log error messages.
	 *
	 * @param appAnalyzer ApplicationAnalysis instance
	 * @param message Log message
	 */
	private static void logError(ApplicationAnalysis appAnalyzer, String message) {
		if (appAnalyzer != null && appAnalyzer.getLogger() != null && appAnalyzer.getLogger().getLogger() != null) {
			appAnalyzer.getLogger().getLogger().severe(message);
		} else {
			System.err.println("[ERROR] " + message);
		}
	}
}
