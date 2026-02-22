package mmmi.se.sdu.dynamic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class IntentExtrasScanner {
	private IntentExtrasScanner() {
	}

	static Map<String, List<IntentOverrides.Extra>> infer(Path decodedDir, Set<String> activityTargets) throws IOException {
		if (decodedDir == null || activityTargets == null || activityTargets.isEmpty()) {
			return Collections.emptyMap();
		}
		Path smaliRoot = decodedDir.resolve("smali");
		if (!Files.exists(smaliRoot)) {
			return Collections.emptyMap();
		}
		Set<String> activitySet = new HashSet<>(activityTargets);
		Map<String, Map<String, IntentOverrides.Extra>> perActivity = new HashMap<>();

		Files.walk(smaliRoot)
				.filter(path -> path.toString().endsWith(".smali"))
				.forEach(path -> {
					try {
						List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
						String className = extractClassName(lines);
						if (className == null || !activitySet.contains(className)) {
							return;
						}
						Map<String, IntentOverrides.Extra> extras = perActivity.computeIfAbsent(className, k -> new HashMap<>());
						Map<String, String> constByReg = new HashMap<>();
						for (String line : lines) {
							String trimmed = line.trim();
							if (trimmed.startsWith("const-string")) {
								String reg = parseRegisterBeforeComma(trimmed);
								String value = parseQuotedString(trimmed);
								if (reg != null && value != null) {
									constByReg.put(reg, value);
								}
								continue;
							}
							if (trimmed.startsWith("move-object")) {
								String[] regs = parseRegisters(trimmed);
								if (regs != null && regs.length >= 2) {
									String src = regs[1];
									String value = constByReg.get(src);
									if (value != null) {
										constByReg.put(regs[0], value);
									}
								}
								continue;
							}

							String type = inferExtraType(trimmed);
							if (type == null) {
								continue;
							}
							String keyReg = getRegisterAt(trimmed, 1);
							if (keyReg == null) {
								continue;
							}
							String key = constByReg.get(keyReg);
							if (key == null || key.isEmpty()) {
								continue;
							}
							if (!extras.containsKey(key)) {
								String defaultValue = defaultValueForType(type);
								extras.put(key, new IntentOverrides.Extra(key, type, defaultValue));
							}
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});

		Map<String, List<IntentOverrides.Extra>> result = new HashMap<>();
		for (Map.Entry<String, Map<String, IntentOverrides.Extra>> entry : perActivity.entrySet()) {
			result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
		}
		return result;
	}

	private static String inferExtraType(String line) {
		String lower = line.toLowerCase(Locale.ROOT);
		if (!lower.contains("get") || !lower.contains("extra") && !lower.contains("bundle")) {
			return null;
		}
		if (lower.contains("getstringextra") || lower.contains("bundle;->getstring")) {
			return "string";
		}
		if (lower.contains("getintextra") || lower.contains("bundle;->getint")) {
			return "int";
		}
		if (lower.contains("getlongextra") || lower.contains("bundle;->getlong")) {
			return "long";
		}
		if (lower.contains("getfloatextra") || lower.contains("bundle;->getfloat")) {
			return "float";
		}
		if (lower.contains("getbooleanextra") || lower.contains("bundle;->getboolean")) {
			return "bool";
		}
		if (lower.contains("geturi")) {
			return "uri";
		}
		return null;
	}

	private static String defaultValueForType(String type) {
		switch (type) {
			case "int":
				return "1";
			case "long":
				return "1";
			case "float":
				return "1.0";
			case "bool":
				return "true";
			case "uri":
				return "https://example.com";
			case "string":
			default:
				return "analysis";
		}
	}

	private static String extractClassName(List<String> lines) {
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith(".class")) {
				int lastSpace = trimmed.lastIndexOf(' ');
				if (lastSpace != -1) {
					String className = trimmed.substring(lastSpace + 1);
					if (className.startsWith("L") && className.endsWith(";")) {
						return className.substring(1, className.length() - 1).replace('/', '.');
					}
				}
			}
		}
		return null;
	}

	private static String parseRegisterBeforeComma(String line) {
		int firstSpace = line.indexOf(' ');
		int comma = line.indexOf(',');
		if (firstSpace < 0 || comma < 0 || comma <= firstSpace) {
			return null;
		}
		return line.substring(firstSpace + 1, comma).trim();
	}

	private static String parseQuotedString(String line) {
		int first = line.indexOf('"');
		int last = line.lastIndexOf('"');
		if (first >= 0 && last > first) {
			return line.substring(first + 1, last);
		}
		return null;
	}

	private static String[] parseRegisters(String line) {
		int braceStart = line.indexOf('{');
		int braceEnd = line.indexOf('}');
		if (braceStart < 0 || braceEnd < 0 || braceStart >= braceEnd) {
			return null;
		}
		String regSection = line.substring(braceStart + 1, braceEnd).trim();
		if (regSection.contains("..")) {
			return null;
		}
		String[] parts = regSection.split(",");
		for (int i = 0; i < parts.length; i++) {
			parts[i] = parts[i].trim();
		}
		return parts;
	}

	private static String getRegisterAt(String line, int index) {
		int braceStart = line.indexOf('{');
		int braceEnd = line.indexOf('}');
		if (braceStart < 0 || braceEnd < 0 || braceStart >= braceEnd) {
			return null;
		}
		String regSection = line.substring(braceStart + 1, braceEnd).trim();
		if (regSection.contains("..")) {
			String[] parts = regSection.split("\\.\\.");
			if (parts.length != 2) {
				return null;
			}
			String start = parts[0].trim();
			if (start.length() < 2) {
				return null;
			}
			char regType = start.charAt(0);
			try {
				int startIndex = Integer.parseInt(start.substring(1));
				return regType + Integer.toString(startIndex + index);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		String[] regs = regSection.split(",");
		if (index < 0 || index >= regs.length) {
			return null;
		}
		return regs[index].trim();
	}
}

