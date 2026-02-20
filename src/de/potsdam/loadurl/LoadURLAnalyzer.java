/**
 * 
 */
package de.potsdam.loadurl;

import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Level;

import de.potsdam.db.LoadUrlDB;
import de.potsdam.main.ApplicationAnalysis;
import de.potsdam.SmaliContent.SmaliContent;
import de.potsdam.constants.GenericConstants;

/**
 * @author abhishektiwari
 *
 */
public class LoadURLAnalyzer {
	
	private static final String TAG = "LoadURLAnalyzer";
	private static int fileCounter = 0;
	private static final String METHOD_MARKER = ".method";

	/**
	 * Analyzes Smali files to extract loadUrl calls from WebView usage.
	 *
	 * This method:
	 * 1. Iterates through all decompiled Smali class files
	 * 2. Identifies classes that use addJavascriptInterface (WebView-JavaScript bridge)
	 * 3. Finds loadUrl() calls within those classes
	 * 4. Performs backward slicing to extract the actual JavaScript/URL content
	 * 5. Stores results in the database for further analysis
	 *
	 * @param appAnalyzer ApplicationAnalysis instance containing parsed Smali content
	 */
	public static void checkLoadUrlType(ApplicationAnalysis appAnalyzer) {
		if (appAnalyzer == null || appAnalyzer.getSmaliData() == null) {
			logError(appAnalyzer, "Invalid application analyzer or Smali data");
			return;
		}

		SmaliContent smaliData = appAnalyzer.getSmaliData();
		fileCounter = 0;

		try {
			for (List<String> classContent : smaliData.classContent) {
				fileCounter++;
				processSmaliClass(appAnalyzer, classContent);
			}
			logInfo(appAnalyzer, "Total Smali files processed: " + fileCounter);
		} catch (Exception e) {
			logError(appAnalyzer, "Error in checkLoadUrlType: " + e.getMessage());
		}
	}

	/**
	 * Processes a single Smali class file to extract loadUrl information.
	 *
	 * @param appAnalyzer The application analyzer
	 * @param classContent List of lines representing a Smali class
	 */
	private static void processSmaliClass(ApplicationAnalysis appAnalyzer, List<String> classContent) {
		if (classContent == null || classContent.isEmpty()) {
			return;
		}

		// Check if class uses addJavascriptInterface
		if (!containsAddJavascriptInterface(classContent)) {
			return;
		}

		String className = extractClassName(classContent.get(0));
		String[] fileContentArray = classContent.toArray(new String[0]);

		// Find and process all loadUrl calls
		for (int i = 0; i < fileContentArray.length; i++) {
			String line = fileContentArray[i];

			if (line.contains(GenericConstants.LOAD_URL)) {
				logInfo(appAnalyzer, "Found loadUrl in class: " + className);
				String extractedContent = extractLoadUrlContent(fileContentArray, i);
				LoadUrlDB.storeIntentDetails(appAnalyzer, className, extractedContent);
			}
		}
	}

	/**
	 * Checks if the Smali class uses addJavascriptInterface.
	 *
	 * @param classContent List of lines in Smali class
	 * @return true if addJavascriptInterface is found
	 */
	private static boolean containsAddJavascriptInterface(List<String> classContent) {
		return classContent.stream()
			.anyMatch(line -> line.contains(GenericConstants.ADDJSInterface));
	}


	/**
	 * Extracts the class name from a Smali class header line.
	 * Expected format: ".class [modifiers] Lpackage/name/ClassName;"
	 *
	 * @param classHeader The class definition line from Smali
	 * @return The extracted class name, or "dummyClass" if parsing fails
	 */
	private static String extractClassName(String classHeader) {
		if (classHeader == null || classHeader.trim().isEmpty()) {
			return "dummyClass";
		}

		try {
			// Parse the class header - format: ".class Lpackage/ClassName;"
			String[] tokens = classHeader.split("\\s+");
			for (String token : tokens) {
				if (token.startsWith("L") && token.endsWith(";")) {
					// Extract the last component after the last '/'
					return token.substring(token.lastIndexOf('/') + 1, token.length() - 1);
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to parse class header: " + classHeader);
		}

		return "dummyClass";
	}


	/**
	 * Extracts the JavaScript/URL content passed to loadUrl by performing backward slicing.
	 *
	 * This method:
	 * 1. Extracts the register that holds the string argument
	 * 2. Walks backward through the code to find where this register is defined
	 * 3. Handles various Smali patterns:
	 *    - const-string: hardcoded strings
	 *    - iget-object: object field values
	 *    - move-result-object: method return values (particularly StringBuilder.toString())
	 *    - sget-object: static field values
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param loadUrlIndex The index of the loadUrl call
	 * @return The extracted JavaScript/URL string or an explanation if unable to extract
	 */
	private static String extractLoadUrlContent(String[] fileContentArray, int loadUrlIndex) {
		if (fileContentArray == null || loadUrlIndex < 0 || loadUrlIndex >= fileContentArray.length) {
			return "Invalid loadUrl index";
		}

		String loadUrlLine = fileContentArray[loadUrlIndex];
		String sourceRegister = extractRegisterFromLoadUrl(loadUrlLine);

		if (sourceRegister == null || sourceRegister.isEmpty()) {
			return "Could not extract source register from: " + loadUrlLine;
		}

		return performBackwardSlicing(fileContentArray, loadUrlIndex, sourceRegister);
	}

	/**
	 * Extracts the register name from a loadUrl invocation.
	 * Format: "invoke-virtual {vX, vY}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V"
	 * Returns vX which contains the string to be loaded.
	 *
	 * @param loadUrlLine The loadUrl invocation line
	 * @return The register name, or null if not found
	 */
	private static String extractRegisterFromLoadUrl(String loadUrlLine) {
		try {
			// Extract register from invoke statement: {v0, v1, ...} or {v0}
			int startBrace = loadUrlLine.indexOf('{');
			int endBrace = loadUrlLine.indexOf('}');

			if (startBrace == -1 || endBrace == -1) {
				return null;
			}

			String registerStr = loadUrlLine.substring(startBrace + 1, endBrace).trim();
			String[] registers = registerStr.split(",");

			// The first register is typically 'this', the second is the string argument
			if (registers.length > 1) {
				return registers[1].trim();
			} else if (registers.length == 1) {
				return registers[0].trim();
			}
		} catch (Exception e) {
			logError(null, "Failed to extract register from: " + loadUrlLine);
		}

		return null;
	}

	/**
	 * Performs backward slicing to find where a register is defined.
	 * Walks backward from the current index to find the constant or method call
	 * that initializes the register. Also handles parameter tracing and method calls.
	 * Integrates advanced StringOptimizer methods for comprehensive string analysis.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param currentIndex Current position in the array
	 * @param targetRegister The register to find
	 * @return The extracted content or descriptive message
	 */
	private static String performBackwardSlicing(String[] fileContentArray, int currentIndex, String targetRegister) {
		int index = currentIndex - 1;

		// Walk backward to find the method entry point
		while (index >= 0 && !fileContentArray[index].contains(METHOD_MARKER)) {
			String line = fileContentArray[index];

			// Case 1: const-string - direct string constant
			if (line.contains(targetRegister) && line.contains("const-string")) {
				String extractedString = extractStringFromConstString(line);
				if (!extractedString.isEmpty()) {
					// Normalize low-SDK ignore marker to a structured tag
					if (extractedString.contains("Ignore addJavascriptInterface due to low Android version")) {
						return "IGNORE_LOW_SDK:addJavascriptInterface method=" + getEnclosingMethodSignature(fileContentArray, index);
					}
					System.out.println("[DEBUG] LoadURL: Found const-string for " + targetRegister + " = \"" + extractedString + "\"");
					return extractedString;
				}
			}

			// Case 2: iget-object - instance field access
			else if (line.contains(targetRegister) && line.contains("iget-object")) {
				String fieldValue = extractFieldValue(fileContentArray, line);
				if (fieldValue != null && !fieldValue.isEmpty()) {
					return fieldValue;
				}
				return "iget-object: " + line;
			}

			// Case 3: move-result-object - method return value
			else if (line.contains(targetRegister) && line.contains("move-result-object")) {
				// Check if StringBuilder.toString() was called
				if (index >= 2 && fileContentArray[index - 2].contains(GenericConstants.STRINGBUILDER_TOSTRING)) {
					return StringOptimizer.string_builder(fileContentArray, index - 2);
				}
				// Try to find the method call that produced this result
				String methodResult = traceMethodCall(fileContentArray, index);
				if (methodResult != null && !methodResult.isEmpty()) {
					return methodResult;
				}
				return "move-result-object: " + line;
			}

			// Case 4: sget-object - static field access
			else if (line.contains(targetRegister) && line.contains("sget-object")) {
				String staticValue = extractStaticFieldValue(fileContentArray, line);
				if (staticValue != null && !staticValue.isEmpty()) {
					return staticValue;
				}
				return "sget-object: " + line;
			}

			// Case 5: move-object - register copy
			else if (line.contains(targetRegister) && line.contains("move-object")) {
				String copiedRegister = extractCopiedRegister(line, targetRegister);
				if (copiedRegister != null && !copiedRegister.isEmpty()) {
					// Recursively trace the source register
					return performBackwardSlicing(fileContentArray, index, copiedRegister);
				}
			}

			// Case 6: invoke-virtual with return value assignment
			else if (line.contains(targetRegister) && (line.contains("invoke-") || line.contains("invoke {"))) {
				String invokeResult = extractInvokeResult(fileContentArray, index, targetRegister);
				if (invokeResult != null && !invokeResult.isEmpty()) {
					return invokeResult;
				}
			}

			// Case 7: Check if register is a parameter (starts with 'p')
			else if (targetRegister.startsWith("p")) {
				// Try to find where this parameter comes from
				String parameterValue = traceParameter(fileContentArray, index, targetRegister);
				if (parameterValue != null && !parameterValue.isEmpty()) {
					return parameterValue;
				}
			}

			// Case 8: Try advanced string concatenation analysis
			String concatResult = StringOptimizer.handleStringConcatenation(fileContentArray, index, targetRegister);
			if (concatResult != null && !concatResult.isEmpty()) {
				return "Concatenated: " + concatResult;
			}

			// Case 9: Try string manipulation chain analysis
			String chainResult = StringOptimizer.analyzeStringManipulationChain(fileContentArray, index, targetRegister);
			if (chainResult != null && !chainResult.isEmpty()) {
				return "Manipulation: " + chainResult;
			}

			// Case 10: Try comprehensive string analysis
			String comprehensiveResult = StringOptimizer.analyzeStringComprehensive(fileContentArray, index, targetRegister);
			if (comprehensiveResult != null && !comprehensiveResult.isEmpty()) {
				return comprehensiveResult;
			}

			index--;
		}

		// Try inter-procedural resolution for parameter registers
		if (targetRegister != null && targetRegister.startsWith("p")) {
			String methodSig = getEnclosingMethodSignature(fileContentArray, currentIndex);
			System.out.println("[DEBUG] LoadURL: Parameter register detected: " + targetRegister + " in method: " + methodSig);
			String fromCaller = resolveFromCallersForString(fileContentArray, methodSig, targetRegister);
			if (!fromCaller.isEmpty()) {
				System.out.println("[DEBUG] LoadURL: Resolved from caller: \"" + fromCaller + "\"");
				return fromCaller;
			}
			// Add typed placeholder for unresolved parameters
			System.out.println("[DEBUG] LoadURL: Could not resolve parameter from caller, returning UNRESOLVED_PARAM");
			return "UNRESOLVED_PARAM: " + targetRegister + " type=String method=" + methodSig;
		}

		System.out.println("[DEBUG] LoadURL: Exhausted all options, returning UNRESOLVED_LOADURL");
		return "UNRESOLVED_LOADURL: register=" + targetRegister
				+ " method=" + getEnclosingMethodSignature(fileContentArray, currentIndex)
				+ " line=" + currentIndex;
	}

	private static String resolveFromCallersForString(String[] fileContentArray, String methodLine, String paramRegister) {
		String descriptor = extractMethodDescriptor(methodLine);
		if (descriptor.isEmpty()) {
			System.out.println("[DEBUG] LoadURL: Could not extract method descriptor from: " + methodLine);
			return "";
		}
		System.out.println("[DEBUG] LoadURL: Looking for callers of method: " + descriptor);

		int paramIndex = getParamIndexFromRegister(methodLine, paramRegister);
		if (paramIndex < 0) {
			System.out.println("[DEBUG] LoadURL: Could not get param index for register: " + paramRegister);
			return "";
		}
		System.out.println("[DEBUG] LoadURL: Parameter " + paramRegister + " maps to argument index: " + paramIndex);

		int methodStart = -1;
		int callersFound = 0;
		for (int i = 0; i < fileContentArray.length; i++) {
			String line = fileContentArray[i];
			if (line == null) {
				continue;
			}
			if (line.contains(METHOD_MARKER)) {
				methodStart = i;
				continue;
			}
			if (line.contains(".end method")) {
				methodStart = -1;
				continue;
			}
			if (methodStart >= 0 && line.contains("invoke") && line.contains("->" + descriptor)) {
				callersFound++;
				System.out.println("[DEBUG] LoadURL: Found caller #" + callersFound + " at line " + i + ": " + line.trim());
				String[] regs = parseInvokeRegistersFlexible(line);
				System.out.println("[DEBUG] LoadURL: Parsed registers: " + java.util.Arrays.toString(regs));
				int argIndex = paramIndex + 1; // instance method receiver at index 0
				if (argIndex < regs.length) {
					String argReg = regs[argIndex];
					System.out.println("[DEBUG] LoadURL: Tracing argument register: " + argReg);
					String resolved = traceStringInMethod(fileContentArray, i, methodStart, argReg);
					if (!resolved.isEmpty()) {
						System.out.println("[DEBUG] LoadURL: Successfully resolved to: \"" + resolved + "\"");
						return resolved;
					}
					System.out.println("[DEBUG] LoadURL: Could not resolve argument register in caller");
				} else {
					System.out.println("[DEBUG] LoadURL: Argument index " + argIndex + " out of bounds (only " + regs.length + " registers)");
				}
			}
		}
		System.out.println("[DEBUG] LoadURL: Total callers found: " + callersFound);
		return "";
	}

	private static String traceStringInMethod(String[] fileContentArray, int index, int methodStart, String register) {
		String currentRegister = register;
		for (int i = index; i >= methodStart; i--) {
			String line = fileContentArray[i];
			if (line == null) {
				continue;
			}
			// Follow register moves
			if (line.contains("move-object") && line.contains(currentRegister)) {
				String[] parts = line.split(",");
				if (parts.length >= 2) {
					String dest = parts[0].trim().split("\\s+")[parts[0].trim().split("\\s+").length - 1];
					if (dest.equals(currentRegister)) {
						currentRegister = parts[1].trim();
						continue;
					}
				}
			}
			// Must match the exact register at the beginning of the instruction
			if ((line.contains("const-string " + currentRegister + ",")
					|| line.contains("const-string/jumbo " + currentRegister + ","))
					&& line.contains("\"")) {
				int firstQuote = line.indexOf('"');
				int lastQuote = line.lastIndexOf('"');
				if (firstQuote >= 0 && lastQuote > firstQuote) {
					String extractedValue = line.substring(firstQuote + 1, lastQuote).trim();
					// Sanity check: if the value looks like it's part of a method signature or invalid, skip it
					if (isValidUrlOrStringValue(extractedValue)) {
						return extractedValue;
					}
					System.out.println("[DEBUG] LoadURL: Skipping suspicious string value: \"" + extractedValue + "\"");
				}
			}
		}
		// If we couldn't find a const-string and the register is a parameter,
		// try to resolve it recursively by finding callers of THIS method
		if (currentRegister != null && currentRegister.startsWith("p")) {
			String callerMethodSig = getEnclosingMethodSignature(fileContentArray, index);
			System.out.println("[DEBUG] LoadURL: Nested parameter " + currentRegister + " in caller method: " + callerMethodSig);
			String nestedResolved = resolveFromCallersForString(fileContentArray, callerMethodSig, currentRegister);
			if (!nestedResolved.isEmpty()) {
				System.out.println("[DEBUG] LoadURL: Resolved nested parameter to: \"" + nestedResolved + "\"");
				return nestedResolved;
			}
		}
		return "";
	}

	/**
	 * Validates if the extracted string is a reasonable URL or string value.
	 * Filters out method signatures, single characters, or obviously malformed strings.
	 */
	private static boolean isValidUrlOrStringValue(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		// Filter out single character strings (likely parsing errors)
		if (value.length() <= 2) {
			return false;
		}
		// Filter out strings that look like method signatures
		if (value.equals(");") || value.equals(")V") || value.equals("()")) {
			return false;
		}
		// Filter out strings that are just punctuation
		if (value.matches("^[^a-zA-Z0-9]+$")) {
			return false;
		}
		return true;
	}

	private static int getParamIndexFromRegister(String methodLine, String register) {
		if (methodLine == null || register == null || !register.startsWith("p")) {
			return -1;
		}
		String normalized = normalizeRegister(register);
		if (normalized.isEmpty()) {
			return -1;
		}
		boolean isStatic = methodLine.contains(" static ");
		int regIndex;
		try {
			regIndex = Integer.parseInt(normalized.substring(1));
		} catch (NumberFormatException e) {
			return -1;
		}
		if (!isStatic) {
			regIndex -= 1; // p0 is 'this' for instance methods
		}
		return regIndex;
	}

	private static String normalizeRegister(String register) {
		String trimmed = register.trim();
		int end = trimmed.length();
		while (end > 0 && !Character.isLetterOrDigit(trimmed.charAt(end - 1))) {
			end--;
		}
		return end > 0 ? trimmed.substring(0, end) : "";
	}

	private static String extractMethodDescriptor(String methodLine) {
		if (methodLine == null || !methodLine.contains("(") || !methodLine.contains(")")) {
			return "";
		}
		String[] parts = methodLine.split("\\s+");
		return parts.length > 0 ? parts[parts.length - 1].trim() : "";
	}

	private static String[] parseInvokeRegistersFlexible(String line) {
		int start = line.indexOf('{');
		int end = line.indexOf('}');
		if (start < 0 || end < 0 || end <= start) {
			return new String[0];
		}
		String inside = line.substring(start + 1, end).trim();
		if (inside.contains("..")) {
			String[] range = inside.split("\\.\\.");
			if (range.length == 2) {
				String startReg = range[0].trim();
				String endReg = range[1].trim();
				if (startReg.length() > 1 && endReg.length() > 1) {
					String prefix = startReg.substring(0, 1);
					try {
						int startNum = Integer.parseInt(startReg.substring(1));
						int endNum = Integer.parseInt(endReg.substring(1));
						int size = Math.max(0, endNum - startNum + 1);
						String[] regs = new String[size];
						for (int i = 0; i < size; i++) {
							regs[i] = prefix + (startNum + i);
						}
						return regs;
					} catch (NumberFormatException e) {
						return new String[0];
					}
				}
			}
			return new String[0];
		}
		String[] parts = inside.split(",");
		for (int i = 0; i < parts.length; i++) {
			parts[i] = parts[i].trim();
		}
		return parts;
	}

	private static String extractCopiedRegister(String line, String targetRegister) {
		try {
			String[] parts = line.split(",");
			if (parts.length >= 2) {
				String[] destParts = parts[0].split("\\s+");
				String dest = destParts[destParts.length - 1].trim();
				if (dest.equals(targetRegister)) {
					return parts[1].trim();
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to extract copied register from: " + line);
		}
		return null;
	}

	private static String extractInvokeResult(String[] fileContentArray, int index, String targetRegister) {
		try {
			for (int i = index - 1; i >= 0 && i >= index - 5; i--) {
				String line = fileContentArray[i];
				if (line.contains("invoke-")) {
					int methodStart = line.indexOf(";->");
					int methodEnd = line.indexOf("(");
					if (methodStart != -1 && methodEnd != -1) {
						String methodName = line.substring(methodStart + 3, methodEnd);
						return "Method result: " + methodName + "()";
					}
					return "Invoked method: " + line.substring(Math.min(50, line.length()));
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to extract invoke result: " + e.getMessage());
		}
		return null;
	}

	private static String traceParameter(String[] fileContentArray, int currentIndex, String parameterRegister) {
		try {
			for (int i = currentIndex; i >= 0 && i >= currentIndex - 20; i--) {
				String line = fileContentArray[i];
				if (line.contains("move-object") && line.contains(parameterRegister)) {
					String[] parts = line.split(",");
					if (parts.length >= 2) {
						String source = parts[1].trim();
						if (!source.startsWith("p")) {
							return performBackwardSlicing(fileContentArray, i, source);
						}
					}
				}
				if (line.contains("const-string") && line.contains(parameterRegister)) {
					String value = extractStringFromConstString(line);
					if (!value.isEmpty()) {
						return value;
					}
				}
				if (line.contains("new-instance") && line.contains(parameterRegister)) {
					return "new-instance: " + line;
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to trace parameter: " + e.getMessage());
		}
		return null;
	}

	private static String extractFieldValue(String[] fileContentArray, String line) {
		try {
			int lastSemicolon = line.lastIndexOf(";");
			int arrowPos = line.indexOf("->");
			if (arrowPos != -1 && lastSemicolon != -1) {
				String fieldDesc = line.substring(arrowPos + 2, lastSemicolon + 1);
				String fieldValue = findFieldInitialization(fileContentArray, fieldDesc);
				if (fieldValue != null && !fieldValue.isEmpty()) {
					return fieldValue;
				}
				return "Field: " + fieldDesc;
			}
		} catch (Exception e) {
			logError(null, "Failed to extract field value: " + e.getMessage());
		}
		return null;
	}

	private static String findFieldInitialization(String[] fileContentArray, String fieldDesc) {
		try {
			for (int i = 0; i < fileContentArray.length; i++) {
				String line = fileContentArray[i];
				if (line.contains(".field") && line.contains(fieldDesc)) {
					if (line.contains("=")) {
						int eqPos = line.indexOf("=");
						String value = line.substring(eqPos + 1).trim();
						if (!value.isEmpty() && !value.equals("\"\"")) {
							return value;
						}
					}
				}
				if (line.contains(".field") && line.contains("static")) {
					for (int j = i + 1; j < fileContentArray.length && j < i + 5; j++) {
						if (fileContentArray[j].contains("sput-object") && fileContentArray[j].contains(fieldDesc)) {
							if (j > 0 && fileContentArray[j - 1].contains("const-string")) {
								return extractStringFromConstString(fileContentArray[j - 1]);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to find field initialization: " + e.getMessage());
		}
		return null;
	}

	private static String extractStaticFieldValue(String[] fileContentArray, String line) {
		try {
			int lastSemicolon = line.lastIndexOf(";");
			int arrowPos = line.indexOf("->");
			if (arrowPos != -1 && lastSemicolon != -1) {
				String fieldDesc = line.substring(arrowPos + 2, lastSemicolon + 1);
				String staticValue = findStaticFieldValue(fileContentArray, fieldDesc);
				if (staticValue != null && !staticValue.isEmpty()) {
					return staticValue;
				}
				return "Static field: " + fieldDesc;
			}
		} catch (Exception e) {
			logError(null, "Failed to extract static field value: " + e.getMessage());
		}
		return null;
	}

	private static String findStaticFieldValue(String[] fileContentArray, String fieldDesc) {
		try {
			for (int i = 0; i < fileContentArray.length; i++) {
				String line = fileContentArray[i];
				if (line.contains(".field") && line.contains("static") && line.contains(fieldDesc)) {
					if (line.contains("=")) {
						int eqPos = line.indexOf("=");
						String value = line.substring(eqPos + 1).trim();
						if (!value.isEmpty() && !value.equals("\"\"")) {
							return value;
						}
					}
					if (i + 1 < fileContentArray.length && fileContentArray[i + 1].contains(".annotation")) {
						for (int j = i + 1; j < fileContentArray.length && j < i + 20; j++) {
							if (fileContentArray[j].contains("const-string")) {
								return extractStringFromConstString(fileContentArray[j]);
							}
						}
					}
				}
				if (line.contains("sput-object") && line.contains(fieldDesc)) {
					for (int j = i - 1; j >= 0 && j >= i - 5; j--) {
						if (fileContentArray[j].contains("const-string")) {
							return extractStringFromConstString(fileContentArray[j]);
						}
						if (fileContentArray[j].contains("const ") && fileContentArray[j].contains("0x")) {
							return "Constant: " + extractConstantValue(fileContentArray[j]);
						}
					}
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to find static field value: " + e.getMessage());
		}
		return null;
	}

	private static String extractConstantValue(String line) {
		try {
			int commaPos = line.indexOf(",");
			if (commaPos != -1) {
				return line.substring(commaPos + 1).trim();
			}
		} catch (Exception e) {
			logError(null, "Failed to extract constant value from: " + line);
		}
		return null;
	}

	private static String traceMethodCall(String[] fileContentArray, int currentIndex) {
		try {
			for (int i = currentIndex - 1; i >= 0 && i >= currentIndex - 10; i--) {
				String line = fileContentArray[i];
				if (line.contains("invoke-")) {
					int methodStart = line.indexOf(";->");
					int methodEnd = line.indexOf("(");
					if (methodStart != -1 && methodEnd != -1) {
						String methodName = line.substring(methodStart + 3, methodEnd);
						String className = "";
						int classStart = line.lastIndexOf("L");
						if (classStart != -1) {
							className = line.substring(classStart, methodStart + 1);
						}
						int paramEnd = line.indexOf(")", methodEnd);
						String returnType = "";
						if (paramEnd != -1 && paramEnd + 1 < line.length()) {
							returnType = line.substring(paramEnd + 1);
						}
						return "Method call: " + className + "->" + methodName + "() returns " + returnType;
					}
					return "Method call: " + line.substring(Math.min(70, line.length()));
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to trace method call: " + e.getMessage());
		}
		return null;
	}

	private static String extractStringFromConstString(String line) {
		try {
			int firstQuote = line.indexOf('"');
			int lastQuote = line.lastIndexOf('"');
			if (firstQuote != -1 && lastQuote != -1 && firstQuote < lastQuote) {
				return line.substring(firstQuote + 1, lastQuote);
			}
		} catch (Exception e) {
			logError(null, "Failed to extract string from: " + line);
		}

		return "";
	}

	/**
	 * Logs an informational message.
	 *
	 * @param appAnalyzer The application analyzer (can be null)
	 * @param message The message to log
	 */
	private static void logInfo(ApplicationAnalysis appAnalyzer, String message) {
		if (appAnalyzer != null && appAnalyzer.getLogger() != null) {
			appAnalyzer.getLogger().getLogger().info(message);
		} else {
			System.out.println("[INFO] " + message);
		}
	}

	/**
	 * Logs an error message.
	 *
	 * @param appAnalyzer The application analyzer (can be null)
	 * @param message The error message to log
	 */
	private static void logError(ApplicationAnalysis appAnalyzer, String message) {
		if (appAnalyzer != null && appAnalyzer.getLogger() != null) {
			appAnalyzer.getLogger().getLogger().severe(message);
		} else {
			System.err.println("[ERROR] " + message);
		}
	}

	private static String getEnclosingMethodSignature(String[] fileContentArray, int index) {
		int i = Math.min(index, fileContentArray.length - 1);
		while (i >= 0) {
			String line = fileContentArray[i];
			if (line != null && line.contains(METHOD_MARKER)) {
				return line.trim();
			}
			i--;
		}
		return "<unknown>";
	}
}
