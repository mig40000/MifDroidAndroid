package mmmi.se.sdu.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Correlates runtime WebView logs with static analysis data from webview_prime table.
 */
final class WebViewCorrelator {

	static class Correlation {
		String context;
		String interfaceObject;
		String bridgeClass;
		String loadUrl;
		String loadData;
		String evaluateJs;
		String timestamp;
		List<String> bridgeMethodsFromDb;

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("Context: ").append(context).append("\n");
			if (interfaceObject != null) {
				sb.append("  Interface Object: ").append(interfaceObject).append("\n");
			}
			if (bridgeClass != null) {
				sb.append("  Bridge Class: ").append(bridgeClass).append("\n");
			}
			if (bridgeMethodsFromDb != null && !bridgeMethodsFromDb.isEmpty()) {
				sb.append("  Bridge Methods (from static analysis):\n");
				for (String method : bridgeMethodsFromDb) {
					sb.append("    - ").append(method).append("\n");
				}
			}
			if (loadUrl != null) {
				sb.append("  loadUrl: ").append(loadUrl).append("\n");
			}
			if (loadData != null) {
				sb.append("  loadData: ").append(truncate(loadData, 500)).append("\n");
			}
			if (evaluateJs != null) {
				sb.append("  evaluateJavascript: ").append(truncate(evaluateJs, 500)).append("\n");
			}
			return sb.toString();
		}

		private String truncate(String str, int maxLen) {
			if (str == null || str.length() <= maxLen) {
				return str;
			}
			return str.substring(0, maxLen) + "... [truncated]";
		}
	}

	static class DbInfo {
		List<String> interfaceObjects = new ArrayList<>();
		List<String> bridgeMethods = new ArrayList<>();
	}

	static void correlateAndReport(Path logFile, String dbPath, Path outputFile) throws IOException, SQLException {
		List<String> logLines = Files.readAllLines(logFile);
		Map<String, DbInfo> contextToDbInfo = loadInfoFromDb(dbPath);
		List<Correlation> correlations = new ArrayList<>();

		String currentContext = null;
		Correlation current = null;

		for (String line : logLines) {
			if (!line.contains("IIFA-WebView")) {
				continue;
			}

			String contextMatch = extractContext(line);
			if (contextMatch != null && !contextMatch.equals(currentContext)) {
				if (current != null) {
					correlations.add(current);
				}
				currentContext = contextMatch;
				current = new Correlation();
				current.context = currentContext;

				// Look up info from database
				DbInfo dbInfo = contextToDbInfo.get(normalizeClassName(currentContext));
				if (dbInfo != null) {
					if (!dbInfo.interfaceObjects.isEmpty()) {
						current.interfaceObject = String.join(", ", dbInfo.interfaceObjects);
					}
					if (!dbInfo.bridgeMethods.isEmpty()) {
						current.bridgeMethodsFromDb = dbInfo.bridgeMethods;
					}
				}
			}

			if (current == null) {
				current = new Correlation();
				current.context = "unknown";
			}

			if (line.contains("IIFA-WebView-loadUrl")) {
				String url = extractValue(line, "URL: ");
				if (url != null) {
					current.loadUrl = url;
				}
			} else if (line.contains("IIFA-WebView-loadData")) {
				String data = extractValue(line, "Data: ");
				if (data != null) {
					current.loadData = data;
				}
			} else if (line.contains("IIFA-WebView-evaluateJavascript")) {
				String js = extractValue(line, "JS: ");
				if (js != null) {
					current.evaluateJs = js;
				}
			} else if (line.contains("IIFA-WebView-addJavascriptInterface")) {
				// Format: [Context: X] Interface: Y Bridge: Z
				String iface = extractInterfaceName(line);
				String bridge = extractBridgeName(line);
				if (iface != null && !iface.isEmpty() && !iface.equals("] Interface:")) {
					current.interfaceObject = iface;
				}
				if (bridge != null && !bridge.isEmpty()) {
					current.bridgeClass = bridge;
				}
			}
		}

		if (current != null) {
			correlations.add(current);
		}

		// Write report
		List<String> report = new ArrayList<>();
		report.add("========================================");
		report.add("WebView Runtime Correlation Report");
		report.add("========================================\n");
		report.add("Total contexts found: " + correlations.size() + "\n");

		for (Correlation corr : correlations) {
			report.add("----------------------------------------");
			report.add(corr.toString());
		}

		Files.write(outputFile, report);

		System.out.println("\n========================================");
		System.out.println("Correlation Report Summary");
		System.out.println("========================================");
		System.out.println("Total contexts: " + correlations.size());
		int withInterface = 0;
		int withLoadUrl = 0;
		int withLoadData = 0;
		int withJs = 0;
		int withBridgeMethods = 0;
		for (Correlation c : correlations) {
			if (c.interfaceObject != null) withInterface++;
			if (c.loadUrl != null) withLoadUrl++;
			if (c.loadData != null) withLoadData++;
			if (c.evaluateJs != null) withJs++;
			if (c.bridgeMethodsFromDb != null && !c.bridgeMethodsFromDb.isEmpty()) withBridgeMethods++;
		}
		System.out.println("Contexts with interface objects: " + withInterface);
		System.out.println("Contexts with loadUrl: " + withLoadUrl);
		System.out.println("Contexts with loadData: " + withLoadData);
		System.out.println("Contexts with evaluateJavascript: " + withJs);
		System.out.println("Contexts with bridge methods (from DB): " + withBridgeMethods);
		System.out.println("\nDetailed report: " + outputFile);
	}

	private static Map<String, DbInfo> loadInfoFromDb(String dbPath) throws SQLException {
		Map<String, DbInfo> result = new HashMap<>();
		String jdbcUrl = "jdbc:sqlite:" + dbPath;

		try (Connection conn = DriverManager.getConnection(jdbcUrl);
		     PreparedStatement stmt = conn.prepareStatement(
		             "SELECT readableInitiatingClass, intefaceObject, bridgeMethods FROM webview_prime WHERE (intefaceObject IS NOT NULL AND intefaceObject != '') OR (bridgeMethods IS NOT NULL AND bridgeMethods != '')")) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				String className = rs.getString(1);
				String iface = rs.getString(2);
				String methods = rs.getString(3);

				if (className != null) {
					className = normalizeClassName(className);
					DbInfo info = result.computeIfAbsent(className, k -> new DbInfo());

					if (iface != null && !iface.isEmpty()) {
						if (!info.interfaceObjects.contains(iface)) {
							info.interfaceObjects.add(iface);
						}
					}

					if (methods != null && !methods.isEmpty()) {
						// Parse method list (format may vary)
						String[] methodArray = methods.split("\n");
						for (String method : methodArray) {
							String trimmed = method.trim();
							if (!trimmed.isEmpty() && !info.bridgeMethods.contains(trimmed)) {
								info.bridgeMethods.add(trimmed);
							}
						}
					}
				}
			}
		}

		return result;
	}

	private static String normalizeClassName(String className) {
		if (className == null) return null;
		String normalized = className.trim();
		if (normalized.startsWith("L") && normalized.endsWith(";")) {
			normalized = normalized.substring(1, normalized.length() - 1).replace('/', '.');
		}
		return normalized;
	}

	private static String extractContext(String line) {
		int contextStart = line.indexOf("[Context: ");
		if (contextStart == -1) return null;
		int contextEnd = line.indexOf("]", contextStart);
		if (contextEnd == -1) return null;
		return line.substring(contextStart + 10, contextEnd).trim();
	}

	private static String extractValue(String line, String prefix) {
		int start = line.indexOf(prefix);
		if (start == -1) return null;
		start += prefix.length();

		// Find end - either end of line or next bracket
		int end = line.indexOf("[", start);
		if (end == -1) {
			end = line.length();
		}

		String value = line.substring(start, end).trim();
		return value.isEmpty() ? null : value;
	}

	private static String extractInterfaceName(String line) {
		// Format: ] Interface: NAME Bridge: BRIDGE_CLASS
		int interfaceStart = line.indexOf("] Interface: ");
		if (interfaceStart == -1) return null;
		interfaceStart += 13; // Length of "] Interface: "

		int bridgeStart = line.indexOf(" Bridge: ", interfaceStart);
		if (bridgeStart == -1) {
			bridgeStart = line.length();
		}

		String iface = line.substring(interfaceStart, bridgeStart).trim();
		// Filter out malformed entries
		if (iface.isEmpty() || iface.equals("]") || iface.startsWith("] Interface:")) {
			return null;
		}
		return iface;
	}

	private static String extractBridgeName(String line) {
		// Format: ] Interface: NAME Bridge: BRIDGE_CLASS
		int bridgeStart = line.indexOf(" Bridge: ");
		if (bridgeStart == -1) return null;
		bridgeStart += 9; // Length of " Bridge: "

		// Bridge class goes to end of line or next context marker
		int end = line.indexOf("[Context:", bridgeStart);
		if (end == -1) {
			end = line.length();
		}

		String bridge = line.substring(bridgeStart, end).trim();
		return bridge.isEmpty() ? null : bridge;
	}
}

