package mmmi.se.sdu.dynamic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WebViewDbReader {
	static List<String> readInitiatingClasses(String sqlitePath) throws SQLException {
		Set<String> classes = new LinkedHashSet<>();
		String jdbcUrl = "jdbc:sqlite:" + sqlitePath;
		try (Connection connection = DriverManager.getConnection(jdbcUrl);
			 Statement statement = connection.createStatement()) {
			try (ResultSet rs = statement.executeQuery("SELECT * FROM webview_prime")) {
				ResultSetMetaData meta = rs.getMetaData();
				int columnCount = meta.getColumnCount();
				int initiatingClassIndex = findColumnIndex(meta, "initiatingClass");
				int readableInitiatingClassIndex = findColumnIndex(meta, "readableInitiatingClass");

				while (rs.next()) {
					String readable = readableInitiatingClassIndex > 0 ? rs.getString(readableInitiatingClassIndex) : null;
					String smali = initiatingClassIndex > 0 ? rs.getString(initiatingClassIndex) : null;
					String value = sanitizeClassName(readable != null ? readable : smali);
					if (value != null && !value.isEmpty()) {
						classes.add(value);
					}
				}

				if (classes.isEmpty() && columnCount > 0) {
					// Attempt to fall back on column names if schema differs
					for (int i = 1; i <= columnCount; i++) {
						String name = meta.getColumnName(i);
						if (name != null && name.toLowerCase().contains("class")) {
							statement.executeQuery("SELECT " + name + " FROM webview_prime");
							break;
						}
					}
				}
			}
		}
		return new ArrayList<>(classes);
	}

	private static int findColumnIndex(ResultSetMetaData meta, String columnName) throws SQLException {
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			if (columnName.equalsIgnoreCase(meta.getColumnName(i))) {
				return i;
			}
		}
		return -1;
	}

	private static String sanitizeClassName(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.startsWith("L") && trimmed.endsWith(";")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).replace('/', '.');
		}
		return trimmed;
	}
}

