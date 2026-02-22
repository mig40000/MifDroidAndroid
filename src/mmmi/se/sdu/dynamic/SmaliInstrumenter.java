package mmmi.se.sdu.dynamic;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SmaliInstrumenter {
	private static final String WEBVIEW_CLASS = "Landroid/webkit/WebView;";

	private static final String LOAD_URL = "->loadUrl(Ljava/lang/String;)V";
	private static final String LOAD_URL_MAP = "->loadUrl(Ljava/lang/String;Ljava/util/Map;)V";
	private	static final String EVAL_JS = "->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V";
	private static final String LOAD_DATA = "->loadData(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
	private static final String LOAD_DATA_BASE = "->loadDataWithBaseURL(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
	private static final String POST_URL = "->postUrl(Ljava/lang/String;[B)V";
	private static final String ADD_JS_INTERFACE = "->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V";

	static int instrumentDirectory(Path decodedDir) throws IOException {
		List<Path> smaliDirs = findSmaliDirs(decodedDir);
		if (smaliDirs.isEmpty()) {
			System.out.println("[DEBUG] No smali directories found in decoded APK");
			return 0;
		}
		System.out.println("[DEBUG] Found " + smaliDirs.size() + " smali directories");
		for (Path smaliDir : smaliDirs) {
			System.out.println("[DEBUG] Ensuring RuntimeLogger in: " + smaliDir);
			try {
				ensureRuntimeLogger(smaliDir);
				System.out.println("[DEBUG] ✅ RuntimeLogger.smali created successfully");
			} catch (IOException e) {
				System.err.println("[ERROR] Failed to create RuntimeLogger: " + e.getMessage());
				e.printStackTrace();
			}
		}
		int total = 0;
		for (Path smaliDir : smaliDirs) {
			int dirInstrumented = instrumentSmaliTree(smaliDir);
			total += dirInstrumented;
			System.out.println("[DEBUG]   - " + smaliDir.getFileName() + ": " + dirInstrumented + " files instrumented");
		}
		return total;
	}

	private static List<Path> findSmaliDirs(Path decodedDir) throws IOException {
		List<Path> result = new ArrayList<>();
		try {
			Files.list(decodedDir).forEach(path -> {
				String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
				if (Files.isDirectory(path) && name.startsWith("smali")) {
					result.add(path);
				}
			});
		} catch (IOException e) {
			throw e;
		}
		return result;
	}

	private static void ensureRuntimeLogger(Path smaliDir) throws IOException {
		Path target = smaliDir.resolve("mmmi/se/sdu/dynamic/RuntimeLogger.smali");
		Files.createDirectories(target.getParent());
		Files.write(target, RuntimeLoggerSmali.getContent().getBytes(StandardCharsets.UTF_8));
	}

	private static int instrumentSmaliTree(Path root) throws IOException {
		int[] modifiedCount = new int[]{0};
		int[] scannedCount = new int[]{0};
		Files.walk(root)
			.filter(path -> path.toString().endsWith(".smali"))
			.forEach(path -> {
				try {
					scannedCount[0]++;
					if (instrumentSmaliFile(path)) {
						modifiedCount[0]++;
						System.out.println("[DEBUG]     Instrumented: " + path.getFileName());
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		System.out.println("[DEBUG]   Total .smali files scanned: " + scannedCount[0]);
		return modifiedCount[0];
	}

	private static boolean instrumentSmaliFile(Path smaliFile) throws IOException {
		List<String> lines = Files.readAllLines(smaliFile, StandardCharsets.UTF_8);
		List<String> updated = new ArrayList<>();
		boolean changed = false;
		String currentClassName = extractClassNameFromFile(lines);
		boolean inMethod = false;
		boolean contextInjected = false;
		boolean methodHasWebViewCalls = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();

			// Track method boundaries to inject context at method start
			if (trimmed.startsWith(".method")) {
				inMethod = true;
				contextInjected = false;
				methodHasWebViewCalls = methodHasWebViewCalls(lines, i);
				updated.add(line);
				continue;
			}

			if (trimmed.equals(".end method")) {
				inMethod = false;
				updated.add(line);
				continue;
			}

			// Inject context tracking at the first instruction in methods that use WebView
			if (inMethod && !contextInjected && isFirstInstruction(trimmed) && methodHasWebViewCalls) {
				String indent = line.substring(0, line.indexOf(trimmed));
				updated.add(indent + "const-string v0, \"" + currentClassName + "\"");
				updated.add(indent + "invoke-static {v0}, Lmmmi/se/sdu/dynamic/RuntimeLogger;->setContext(Ljava/lang/String;)V");
				// Emit static URL hints early to avoid missing data on early returns
				for (String url : collectStaticUrls(lines, i)) {
					updated.add(indent + "const-string v0, \"" + escapeSmaliString(url) + "\"");
					updated.add(indent + "invoke-static {v0}, Lmmmi/se/sdu/dynamic/RuntimeLogger;->logLoadUrlStatic(Ljava/lang/String;)V");
				}
				// Emit static JS interface hints early for analysis correlation
				for (String iface : collectStaticInterfaces(lines, i)) {
					updated.add(indent + "const-string v0, \"" + escapeSmaliString(iface) + "\"");
					updated.add(indent + "invoke-static {v0}, Lmmmi/se/sdu/dynamic/RuntimeLogger;->logAddJavascriptInterfaceStatic(Ljava/lang/String;)V");
				}
				changed = true;
				contextInjected = true;
			}

			if (isWebViewInvoke(trimmed) && !isAlreadyInstrumented(lines, i)) {
				String indent = line.substring(0, line.indexOf(trimmed));

				if (trimmed.contains(ADD_JS_INTERFACE)) {
					// addJavascriptInterface needs 2 args: interface name (string) and bridge object
					String nameRegister = extractAddJsInterfaceNameRegister(trimmed);
					String objectRegister = extractAddJsInterfaceObjectRegister(trimmed);
					if (nameRegister != null && objectRegister != null) {
						System.out.println("[DEBUG] Instrumenting addJavascriptInterface call in " + currentClassName);
						updated.add(indent + "invoke-static {" + nameRegister + ", " + objectRegister + "}, Lmmmi/se/sdu/dynamic/RuntimeLogger;->logAddJavascriptInterface(Ljava/lang/String;Ljava/lang/Object;)V");
						changed = true;
					}
				} else {
					// Other WebView methods need 1 arg (the URL/JS content)
					String argRegister = extractTargetRegister(trimmed);
					String loggerMethod = resolveLoggerMethod(trimmed);
					if (argRegister != null && loggerMethod != null) {
						System.out.println("[DEBUG] Instrumenting " + loggerMethod + " call in " + currentClassName + " with register: " + argRegister);
						updated.add(indent + "invoke-static {" + argRegister + "}, Lmmmi/se/sdu/dynamic/RuntimeLogger;->" + loggerMethod + "(Ljava/lang/String;)V");
						changed = true;
					}
				}
				updated.add(line);
				continue;
			}

			updated.add(line);
		}

		if (changed) {
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(smaliFile.toFile()), StandardCharsets.UTF_8))) {
				for (String outLine : updated) {
					writer.write(outLine);
					writer.newLine();
				}
			}
		}

		return changed;
	}

	private static boolean methodHasWebViewCalls(List<String> lines, int methodStartIndex) {
		for (int i = methodStartIndex; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.equals(".end method")) {
				return false;
			}
			if (isWebViewInvoke(trimmed)) {
				return true;
			}
		}
		return false;
	}

	private static String extractClassNameFromFile(List<String> lines) {
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
		return "UnknownClass";
	}

	private static boolean isFirstInstruction(String trimmed) {
		return trimmed.startsWith("const") || trimmed.startsWith("move") ||
		       trimmed.startsWith("invoke") || trimmed.startsWith("new-") ||
		       trimmed.startsWith("iget") || trimmed.startsWith("sget") ||
		       trimmed.startsWith("return");
	}

	private static boolean linesContainWebViewCalls(List<String> lines, int fromIndex) {
		for (int i = fromIndex; i < lines.size(); i++) {
			if (lines.get(i).trim().equals(".end method")) {
				break;
			}
			if (isWebViewInvoke(lines.get(i).trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isFragmentLifecycleMethod(String methodSignature) {
		// Fragment lifecycle methods that should execute early and often
		return methodSignature.contains("onCreateView") ||
		       methodSignature.contains("onViewCreated") ||
		       methodSignature.contains("onResume") ||
		       methodSignature.contains("onAttach") ||
		       methodSignature.contains("onCreate(");
	}

	private static String extractAddJsInterfaceNameRegister(String invokeLine) {
		// addJavascriptInterface(Object, String) - name is the 2nd argument
		String[] registers = extractAllRegisters(invokeLine);
		if (registers != null && registers.length >= 3) {
			return registers[2].trim(); // Skip receiver (0) and object (1), get name (2)
		}
		return null;
	}

	private static String extractAddJsInterfaceObjectRegister(String invokeLine) {
		// addJavascriptInterface(Object, String) - object is the 1st argument
		String[] registers = extractAllRegisters(invokeLine);
		if (registers != null && registers.length >= 2) {
			return registers[1].trim(); // Skip receiver (0), get object (1)
		}
		return null;
	}

	private static String[] extractAllRegisters(String invokeLine) {
		int braceStart = invokeLine.indexOf('{');
		int braceEnd = invokeLine.indexOf('}');
		if (braceStart < 0 || braceEnd < 0 || braceStart >= braceEnd) {
			return null;
		}
		String regSection = invokeLine.substring(braceStart + 1, braceEnd).trim();
		if (regSection.contains("..")) {
			return null; // Range format not supported for this
		}
		return regSection.split(",");
	}

	private static boolean isAlreadyInstrumented(List<String> lines, int index) {
		int prev = index - 1;
		if (prev >= 0) {
			return lines.get(prev).contains("Lmmmi/se/sdu/dynamic/RuntimeLogger;");
		}
		return false;
	}

	private static boolean isWebViewInvoke(String line) {
		// Direct WebView class calls
		if (line.contains(WEBVIEW_CLASS) && (
				line.contains(LOAD_URL) ||
				line.contains(LOAD_URL_MAP) ||
				line.contains(EVAL_JS) ||
				line.contains(LOAD_DATA) ||
				line.contains(LOAD_DATA_BASE) ||
				line.contains(POST_URL) ||
				line.contains(ADD_JS_INTERFACE))) {
			return true;
		}

		// Any invoke-* with a WebView-like API (handles subclasses and wrappers)
		if (line.contains("invoke-") && (
				line.contains("->loadUrl(") ||
				line.contains("->evaluateJavascript(") ||
				line.contains("->loadData(") ||
				line.contains("->loadDataWithBaseURL(") ||
				line.contains("->postUrl(") ||
				line.contains("->addJavascriptInterface("))) {
			return true;
		}

		return false;
	}

	private static String resolveLoggerMethod(String line) {
		if (line.contains(LOAD_URL) || line.contains(LOAD_URL_MAP) || line.contains("->loadUrl(")) {
			return "logLoadUrl";
		}
		if (line.contains(EVAL_JS) || line.contains("->evaluateJavascript(")) {
			return "logEvaluateJavascript";
		}
		if (line.contains(LOAD_DATA) || line.contains("->loadData(")) {
			return "logLoadData";
		}
		if (line.contains(LOAD_DATA_BASE) || line.contains("->loadDataWithBaseURL(")) {
			return "logLoadDataWithBaseUrl";
		}
		if (line.contains(POST_URL) || line.contains("->postUrl(")) {
			return "logPostUrl";
		}
		return null;
	}

	private static String extractTargetRegister(String invokeLine) {
		int braceStart = invokeLine.indexOf('{');
		int braceEnd = invokeLine.indexOf('}');
		if (braceStart < 0 || braceEnd < 0 || braceStart >= braceEnd) {
			return null;
		}
		String regSection = invokeLine.substring(braceStart + 1, braceEnd).trim();
		if (regSection.contains("..")) {
			return registerFromRange(regSection, resolveArgIndex(invokeLine));
		}
		String[] registers = regSection.split(",");
		int argIndex = resolveArgIndex(invokeLine);
		if (argIndex < 0 || registers.length <= argIndex) {
			return null;
		}
		return registers[argIndex].trim();
	}

	private static int resolveArgIndex(String line) {
		if (line.contains(LOAD_URL) || line.contains(LOAD_URL_MAP) || line.contains("->loadUrl(") ||
				line.contains(EVAL_JS) || line.contains("->evaluateJavascript(") ||
				line.contains(POST_URL) || line.contains("->postUrl(")) {
			return 1;
		}
		if (line.contains(LOAD_DATA) || line.contains("->loadData(")) {
			return 1;
		}
		if (line.contains(LOAD_DATA_BASE) || line.contains("->loadDataWithBaseURL(")) {
			return 2;
		}
		return -1;
	}

	private static String registerFromRange(String range, int argIndex) {
		String cleaned = range.replace("{", "").replace("}", "").trim();
		String[] parts = cleaned.split("\\.\\.");
		if (parts.length != 2) {
			return null;
		}
		String start = parts[0].trim();
		String end = parts[1].trim();
		if (start.length() < 2 || end.length() < 2) {
			return null;
		}
		char regType = start.charAt(0);
		try {
			int startIndex = Integer.parseInt(start.substring(1));
			int endIndex = Integer.parseInt(end.substring(1));
			int targetIndex = startIndex + argIndex;
			if (targetIndex > endIndex) {
				return null;
			}
			return regType + Integer.toString(targetIndex);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static List<String> collectStaticUrls(List<String> lines, int fromIndex) {
		List<String> urls = new ArrayList<>();
		for (int i = fromIndex; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.equals(".end method")) {
				break;
			}
			if (trimmed.startsWith("const-string")) {
				int firstQuote = trimmed.indexOf('"');
				int lastQuote = trimmed.lastIndexOf('"');
				if (firstQuote >= 0 && lastQuote > firstQuote) {
					String literal = trimmed.substring(firstQuote + 1, lastQuote);
					if (looksLikeUrl(literal) && !urls.contains(literal)) {
						urls.add(literal);
					}
				}
			}
		}
		return urls;
	}

	private static boolean looksLikeUrl(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://") ||
				lower.startsWith("file://") || lower.startsWith("javascript:") ||
				lower.startsWith("data:");
	}

	private static String escapeSmaliString(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static List<String> collectStaticInterfaces(List<String> lines, int fromIndex) {
		List<String> entries = new ArrayList<>();
		for (int i = fromIndex; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.equals(".end method")) {
				break;
			}
			if (trimmed.contains(ADD_JS_INTERFACE) && trimmed.contains("invoke-")) {
				String[] regs = extractAllRegisters(trimmed);
				if (regs == null || regs.length < 3) {
					continue;
				}
				String objReg = regs[1].trim();
				String nameReg = regs[2].trim();
				String name = findConstString(lines, i, nameReg);
				String bridge = findNewInstanceClass(lines, i, objReg);
				if (name != null) {
					String entry = bridge == null ? name : name + " | Bridge: " + bridge;
					if (!entries.contains(entry)) {
						entries.add(entry);
					}
				}
			}
		}
		return entries;
	}

	private static String findConstString(List<String> lines, int fromIndex, String register) {
		for (int i = fromIndex; i >= 0; i--) {
			String trimmed = lines.get(i).trim();
			if (trimmed.equals(".end method")) {
				break;
			}
			if (trimmed.startsWith("const-string") && trimmed.contains(register + ",")) {
				int firstQuote = trimmed.indexOf('"');
				int lastQuote = trimmed.lastIndexOf('"');
				if (firstQuote >= 0 && lastQuote > firstQuote) {
					return trimmed.substring(firstQuote + 1, lastQuote);
				}
			}
		}
		return null;
	}

	private static String findNewInstanceClass(List<String> lines, int fromIndex, String register) {
		for (int i = fromIndex; i >= 0; i--) {
			String trimmed = lines.get(i).trim();
			if (trimmed.equals(".end method")) {
				break;
			}
			if (trimmed.startsWith("new-instance") && trimmed.contains(register + ",")) {
				int comma = trimmed.indexOf(',');
				if (comma > 0) {
					String type = trimmed.substring(comma + 1).trim();
					if (type.startsWith("L") && type.endsWith(";")) {
						return type.substring(1, type.length() - 1).replace('/', '.');
					}
				}
			}
		}
		return null;
	}
}
