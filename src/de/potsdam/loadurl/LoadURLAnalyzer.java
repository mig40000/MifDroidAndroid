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

		return "UNRESOLVED_LOADURL: register=" + targetRegister
				+ " method=" + getEnclosingMethodSignature(fileContentArray, currentIndex)
				+ " line=" + currentIndex;
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

	/**
	 * Extracts the source register from a move-object instruction.
	 * Format: "move-object vX, vY" -> returns vY
	 *
	 * @param line The move-object instruction
	 * @param targetRegister The register we're looking for
	 * @return The source register, or null if not found
	 */
	private static String extractCopiedRegister(String line, String targetRegister) {
		try {
			String[] parts = line.split(",");
			if (parts.length >= 2) {
				// The target is parts[0], source is parts[1]
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

	/**
	 * Extracts information from an invoke call result.
	 * Tries to find what method was called and what it returns.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param index Current index
	 * @param targetRegister The register receiving the result
	 * @return The method information or extracted value
	 */
	private static String extractInvokeResult(String[] fileContentArray, int index, String targetRegister) {
		try {
			// Look backward from current position for the invoke call
			for (int i = index - 1; i >= 0 && i >= index - 5; i--) {
				String line = fileContentArray[i];
				if (line.contains("invoke-")) {
					// Extract method signature
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

	/**
	 * Traces a parameter value by finding where it's used or transformed.
	 * Parameters (p0, p1, etc.) are method parameters that may be assigned
	 * from calling context.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param currentIndex Current index in file
	 * @param parameterRegister The parameter register (e.g., p0, p1)
	 * @return Traced value or null
	 */
	private static String traceParameter(String[] fileContentArray, int currentIndex, String parameterRegister) {
		try {
			// Look for assignments from this parameter
			for (int i = currentIndex; i >= 0 && i >= currentIndex - 20; i--) {
				String line = fileContentArray[i];

				// Case 1: move-object from parameter
				if (line.contains("move-object") && line.contains(parameterRegister)) {
					String[] parts = line.split(",");
					if (parts.length >= 2) {
						String source = parts[1].trim();
						// If source is not another parameter, trace it
						if (!source.startsWith("p")) {
							return performBackwardSlicing(fileContentArray, i, source);
						}
					}
				}

				// Case 2: used directly in const construction
				if (line.contains("const-string") && line.contains(parameterRegister)) {
					String value = extractStringFromConstString(line);
					if (!value.isEmpty()) {
						return value;
					}
				}

				// Case 3: new-instance with parameter
				if (line.contains("new-instance") && line.contains(parameterRegister)) {
					return "new-instance: " + line;
				}
			}
		} catch (Exception e) {
			logError(null, "Failed to trace parameter: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Attempts to extract the actual value from an iget-object field access.
	 * Tries to find the field definition and extract its initial value.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param line The iget-object instruction
	 * @return The field value or reference information
	 */
	private static String extractFieldValue(String[] fileContentArray, String line) {
		try {
			// Extract field signature from iget-object
			// Format: iget-object vX, vY, Lclass;->field:Ltype;
			int lastSemicolon = line.lastIndexOf(";");
			int arrowPos = line.indexOf("->");

			if (arrowPos != -1 && lastSemicolon != -1) {
				String fieldDesc = line.substring(arrowPos + 2, lastSemicolon + 1);

				// Try to find the field definition in the class
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

	/**
	 * Searches for field initialization in the Smali code.
	 * Looks for static initializer or field assignment patterns.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param fieldDesc Field description (fieldName:Type)
	 * @return The initialized value or null
	 */
	private static String findFieldInitialization(String[] fileContentArray, String fieldDesc) {
		try {
			// Look for .field declarations or static initializers
			for (int i = 0; i < fileContentArray.length; i++) {
				String line = fileContentArray[i];

				// Check for .field declaration with value
				if (line.contains(".field") && line.contains(fieldDesc)) {
					// Extract value from field declaration
					if (line.contains("=")) {
						int eqPos = line.indexOf("=");
						String value = line.substring(eqPos + 1).trim();
						if (!value.isEmpty() && !value.equals("\"\"")) {
							return value;
						}
					}
				}

				// Check for static initializer that sets this field
				if (line.contains(".field") && line.contains("static")) {
					// Look ahead for initialization
					for (int j = i + 1; j < fileContentArray.length && j < i + 5; j++) {
						if (fileContentArray[j].contains("sput-object") && fileContentArray[j].contains(fieldDesc)) {
							// Found the initialization, look backward for the value
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

	/**
	 * Attempts to extract information from a static field access.
	 * Tries to find the actual static field value if possible.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param line The sget-object instruction
	 * @return The static field value or reference
	 */
	private static String extractStaticFieldValue(String[] fileContentArray, String line) {
		try {
			// Extract static field signature
			// Format: sget-object vX, Lclass;->FIELD:Ltype;
			int lastSemicolon = line.lastIndexOf(";");
			int arrowPos = line.indexOf("->");

			if (arrowPos != -1 && lastSemicolon != -1) {
				String fieldDesc = line.substring(arrowPos + 2, lastSemicolon + 1);

				// Try to find the static field initialization
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

	/**
	 * Searches for static field value initialization in the code.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param fieldDesc Field description
	 * @return The static field value or null
	 */
	private static String findStaticFieldValue(String[] fileContentArray, String fieldDesc) {
		try {
			for (int i = 0; i < fileContentArray.length; i++) {
				String line = fileContentArray[i];

				// Look for .field static declaration
				if (line.contains(".field") && line.contains("static") && line.contains(fieldDesc)) {
					// Try to extract value from declaration
					if (line.contains("=")) {
						int eqPos = line.indexOf("=");
						String value = line.substring(eqPos + 1).trim();
						if (!value.isEmpty() && !value.equals("\"\"")) {
							return value;
						}
					}

					// Look for static initializer block
					if (i + 1 < fileContentArray.length && fileContentArray[i + 1].contains(".annotation")) {
						// Skip annotations and look for actual initialization
						for (int j = i + 1; j < fileContentArray.length && j < i + 20; j++) {
							if (fileContentArray[j].contains("const-string")) {
								return extractStringFromConstString(fileContentArray[j]);
							}
						}
					}
				}

				// Look for sput-object patterns that initialize this field
				if (line.contains("sput-object") && line.contains(fieldDesc)) {
					// Look backward for where the value comes from
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

	/**
	 * Extracts a constant value from a const instruction.
	 * Format: "const vX, 0xValue"
	 *
	 * @param line The const instruction line
	 * @return The constant value or null
	 */
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

	/**
	 * Traces method calls to extract return values and method information.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param currentIndex Current index
	 * @return Method information or extracted value
	 */
	private static String traceMethodCall(String[] fileContentArray, int currentIndex) {
		try {
			// Look backward for invoke statements
			for (int i = currentIndex - 1; i >= 0 && i >= currentIndex - 10; i--) {
				String line = fileContentArray[i];

				if (line.contains("invoke-")) {
					// Extract the method being called
					int methodStart = line.indexOf(";->");
					int methodEnd = line.indexOf("(");

					if (methodStart != -1 && methodEnd != -1) {
						String methodName = line.substring(methodStart + 3, methodEnd);
						String className = "";

						// Extract class name
						int classStart = line.lastIndexOf("L");
						if (classStart != -1) {
							className = line.substring(classStart, methodStart + 1);
						}

						// Extract method parameters to determine return type
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

	/**
	 * Extracts the string literal from a const-string Smali instruction.
	 * Format: "const-string vX, \"string content\""
	 *
	 * @param line The const-string instruction line
	 * @return The extracted string or empty string if parsing fails
	 */
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
}
