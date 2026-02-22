package mmmi.se.sdu.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches the jsdetails table with actual PASS_STRING values from runtime analysis.
 *
 * Reads webview-filtered.txt containing captured loadUrl, loadData, and evaluateJavascript calls
 * and updates jsdetails table with:
 * - Actual PASS_STRING values (URLs, JavaScript code)
 * - High confidence scores for runtime-captured data
 * - Resolution type as "DYNAMIC"
 * - Source hints indicating where the value came from
 */
public class DynamicAnalysisEnricher {

    private static final String DB_URL = "jdbc:sqlite:Database/Intent.sqlite";
    private static final Pattern CONTEXT_PATTERN = Pattern.compile("\\[Context: ([^\\]]+)\\]");
    // Updated to match both "URL: " and "URL(static): " formats
    private static final Pattern URL_PATTERN = Pattern.compile("URL(?:\\(static\\))?: (.+?)(?:\\s*$|\\s*[^\\s])");
    // Updated to match both "JS: " and "JS(static): " formats
    private static final Pattern JS_PATTERN = Pattern.compile("JS(?:\\(static\\))?: (.+?)(?:\\s*$|\\s*[^\\s])");
    // Updated to match both "Data: " and "Data(static): " formats
    private static final Pattern DATA_PATTERN = Pattern.compile("Data(?:\\(static\\))?: (.+?)(?:\\s*$|\\s*[^\\s])");
    // Updated to match both "Interface: " and "Interface(static): " formats
    private static final Pattern INTERFACE_PATTERN = Pattern.compile("Interface(?:\\(static\\))?: ([^\\s|]+)");
    // Updated to match both "Bridge: " and "Bridge(static): " formats
    private static final Pattern BRIDGE_PATTERN = Pattern.compile("Bridge(?:\\(static\\))?: (.+?)(?:\\s*$|\\s*[^\\s])");

    /**
     * Enriches jsdetails table with runtime-captured JavaScript and URL values.
     *
     * @param webviewFilteredPath Path to webview-filtered.txt
     * @param appName Application being analyzed
     */
    public static void enrichJsDetails(Path webviewFilteredPath, String appName) {
        if (!Files.exists(webviewFilteredPath)) {
            System.out.println("ERROR: webview-filtered.txt not found at " + webviewFilteredPath);
            return;
        }

        System.out.println("[DEBUG] Starting enrichment for app: " + appName);
        System.out.println("[DEBUG] Reading from: " + webviewFilteredPath);

        Map<String, RuntimeCall> runtimeCalls = parseWebViewLogs(webviewFilteredPath, appName);

        System.out.println("[DEBUG] Parsed " + runtimeCalls.size() + " runtime calls");

        if (runtimeCalls.isEmpty()) {
            System.out.println("WARNING: No runtime calls parsed from webview-filtered.txt");
            return;
        }

        System.out.println("[DEBUG] Runtime calls found:");
        for (String key : runtimeCalls.keySet()) {
            RuntimeCall call = runtimeCalls.get(key);
            System.out.println("[DEBUG]   - " + key + ": " + call.type + " -> " + call.value.substring(0, Math.min(80, call.value.length())));
        }

        updateJsDetailsTable(runtimeCalls, appName);
    }

    /**
     * Parses webview-filtered.txt to extract runtime WebView calls.
     */
    private static Map<String, RuntimeCall> parseWebViewLogs(Path logPath, String appName) {
        Map<String, RuntimeCall> calls = new LinkedHashMap<>();

        try {
            List<String> lines = Files.readAllLines(logPath);

            for (String line : lines) {
                RuntimeCall call = parseLogLine(line, appName);
                if (call != null) {
                    // Use context + type as key to avoid duplicates
                    String key = call.context + "|" + call.type;
                    if (!calls.containsKey(key)) {
                        calls.put(key, call);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("ERROR reading webview-filtered.txt: " + e.getMessage());
        }

        System.out.println("Parsed " + calls.size() + " runtime WebView calls");
        return calls;
    }

    /**
     * Parses a single logcat line to extract WebView call details.
     */
    private static RuntimeCall parseLogLine(String line, String appName) {
        if (!line.contains("IIFA-WebView")) {
            return null;
        }

        RuntimeCall call = new RuntimeCall();

        // Extract context
        Matcher contextMatcher = CONTEXT_PATTERN.matcher(line);
        if (contextMatcher.find()) {
            call.context = contextMatcher.group(1).trim();
        } else {
            return null;
        }

        // Extract type and value based on log type
        if (line.contains("IIFA-WebView-loadUrl")) {
            call.type = "loadUrl";
            Matcher urlMatcher = URL_PATTERN.matcher(line);
            if (urlMatcher.find()) {
                call.value = urlMatcher.group(1).trim();
            }
        } else if (line.contains("IIFA-WebView-loadData")) {
            call.type = "loadData";
            Matcher dataMatcher = DATA_PATTERN.matcher(line);
            if (dataMatcher.find()) {
                call.value = dataMatcher.group(1).trim();
            }
        } else if (line.contains("IIFA-WebView-evaluateJavascript")) {
            call.type = "evaluateJavascript";
            Matcher jsMatcher = JS_PATTERN.matcher(line);
            if (jsMatcher.find()) {
                call.value = jsMatcher.group(1).trim();
            }
        } else if (line.contains("IIFA-WebView-addJavascriptInterface")) {
            call.type = "addJavascriptInterface";
            // Extract Interface and Bridge info using patterns
            Matcher interfaceMatcher = INTERFACE_PATTERN.matcher(line);
            Matcher bridgeMatcher = BRIDGE_PATTERN.matcher(line);
            String interfaceName = null;
            String bridgeName = null;
            if (interfaceMatcher.find()) {
                interfaceName = interfaceMatcher.group(1).trim();
            }
            if (bridgeMatcher.find()) {
                bridgeName = bridgeMatcher.group(1).trim();
            }
            if (interfaceName != null && bridgeName != null) {
                call.value = interfaceName + " -> " + bridgeName;
            }
        }

        // Only keep calls with actual values
        if (call.value != null && !call.value.isEmpty()) {
            call.appName = appName;
            return call;
        }

        return null;
    }

    /**
     * Updates jsdetails table with runtime-captured values.
     */
    private static void updateJsDetailsTable(Map<String, RuntimeCall> calls, String appName) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            int updated = 0;
            int inserted = 0;

            String selectSql = "SELECT id, PASS_STRING FROM jsdetails WHERE PACKAGE_NAME = ? AND ACTIVITY_NAME = ?";
            String insertSql = "INSERT INTO jsdetails " +
                "(PACKAGE_NAME, ACTIVITY_NAME, PASS_STRING, confidence, resolution_type, source_hint, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
            String updateSql = "UPDATE jsdetails SET " +
                "PASS_STRING = ?, confidence = 0.95, resolution_type = 'DYNAMIC', " +
                "source_hint = ?, timestamp = ? " +
                "WHERE PACKAGE_NAME = ? AND ACTIVITY_NAME = ?";

            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                 PreparedStatement insertStmt = conn.prepareStatement(insertSql);
                 PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {

                long timestamp = System.currentTimeMillis();

                for (RuntimeCall call : calls.values()) {
                    System.out.println("[DEBUG] Processing: " + call.context + " (" + call.type + ")");

                    selectStmt.setString(1, appName);
                    selectStmt.setString(2, call.context);

                    ResultSet rs = selectStmt.executeQuery();

                    if (rs.next()) {
                        System.out.println("[DEBUG]   -> Updating existing entry");
                        // Update existing entry
                        updateStmt.setString(1, call.value);
                        updateStmt.setString(2, call.type + "_" + call.context);
                        updateStmt.setLong(3, timestamp);
                        updateStmt.setString(4, appName);
                        updateStmt.setString(5, call.context);
                        updateStmt.addBatch();
                        updated++;
                    } else {
                        System.out.println("[DEBUG]   -> Inserting new entry");
                        // Insert new entry
                        insertStmt.setString(1, appName);
                        insertStmt.setString(2, call.context);
                        insertStmt.setString(3, call.value);
                        insertStmt.setDouble(4, 0.95); // High confidence for runtime data
                        insertStmt.setString(5, "DYNAMIC");
                        insertStmt.setString(6, call.type);
                        insertStmt.setLong(7, timestamp);
                        insertStmt.addBatch();
                        inserted++;
                    }
                }

                updateStmt.executeBatch();
                insertStmt.executeBatch();
                conn.commit();

                System.out.println("[DEBUG] ✅ Enrichment complete:");
                System.out.println("[DEBUG]   - Updated: " + updated);
                System.out.println("[DEBUG]   - Inserted: " + inserted);
                System.out.println("[DEBUG]   - Total: " + (updated + inserted));
            }

        } catch (SQLException e) {
            System.out.println("ERROR updating jsdetails table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Prints a summary of enriched data.
     */
    public static void printEnrichmentSummary(String appName) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) as total, " +
                "COUNT(CASE WHEN resolution_type = 'DYNAMIC' THEN 1 END) as dynamic, " +
                "COUNT(CASE WHEN confidence > 0.9 THEN 1 END) as high_conf " +
                "FROM jsdetails WHERE PACKAGE_NAME = ?")) {

            stmt.setString(1, appName);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                System.out.println("\n========================================");
                System.out.println("Dynamic Enrichment Summary");
                System.out.println("========================================");
                System.out.println("App: " + appName);
                System.out.println("Total entries: " + rs.getInt("total"));
                System.out.println("Dynamic entries: " + rs.getInt("dynamic"));
                System.out.println("High confidence: " + rs.getInt("high_conf"));
                System.out.println("========================================");
            }
        } catch (SQLException e) {
            System.out.println("ERROR printing summary: " + e.getMessage());
        }
    }

    /**
     * Data class representing a runtime WebView call.
     */
    static class RuntimeCall {
        String appName;
        String context;      // Activity/Fragment class
        String type;          // loadUrl, loadData, evaluateJavascript, addJavascriptInterface
        String value;         // The actual URL, JavaScript code, or data
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: DynamicAnalysisEnricher <webview-filtered.txt> <appName>");
            System.exit(1);
        }

        Path logPath = Paths.get(args[0]);
        String appName = args[1];

        enrichJsDetails(logPath, appName);
        printEnrichmentSummary(appName);
    }
}



