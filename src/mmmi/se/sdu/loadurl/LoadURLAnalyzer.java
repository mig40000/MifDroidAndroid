/**
 * 
 */
package mmmi.se.sdu.loadurl;

import java.util.List;

import mmmi.se.sdu.db.LoadUrlDB;
import mmmi.se.sdu.db.ResolutionConfidence;
import mmmi.se.sdu.main.ApplicationAnalysis;
import mmmi.se.sdu.SmaliContent.SmaliContent;
import mmmi.se.sdu.constants.GenericConstants;

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
	 * PHASE 1 ENHANCED: Now includes confidence scoring, dynamic pattern detection, and partial extraction
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

				// PHASE 1: Determine confidence level
				ResolutionConfidence confidence;
				if (extractedContent.startsWith("INFERRED_ASSET:")) {
					confidence = ResolutionConfidence.INFERRED_ASSETS;
				} else if (extractedContent.startsWith("UNRESOLVED")) {
					confidence = ResolutionConfidence.UNKNOWN;
				} else if (extractedContent.isEmpty() || extractedContent.equals("") || extractedContent.equals("Could not trace")) {
					confidence = ResolutionConfidence.UNKNOWN;
				} else {
					confidence = ResolutionConfidence.STATIC_CONFIRMED;
				}

				// PHASE 1: Detect dynamic patterns
				String dynamicPatterns = detectDynamicPatterns(fileContentArray, i);
				if (!dynamicPatterns.isEmpty()) {
					confidence = ResolutionConfidence.MARKED_DYNAMIC;
				}

				// PHASE 1: Extract partial info if needed
				LoadUrlDB.PartialInfo partialInfo = new LoadUrlDB.PartialInfo();
				if (confidence == ResolutionConfidence.UNKNOWN ||
					confidence == ResolutionConfidence.MARKED_DYNAMIC ||
					extractedContent.startsWith("UNRESOLVED")) {

					int methodStart = findMethodStart(fileContentArray, i);
					partialInfo = extractPartialInfo(fileContentArray, i, methodStart);
				}

				// PHASE 1: Try asset inference if still unresolved
				if (extractedContent.isEmpty() || extractedContent.startsWith("UNRESOLVED")) {
					String inferred = inferFromAssets(appAnalyzer);
					if (!inferred.isEmpty()) {
						extractedContent = "INFERRED_ASSET: " + inferred;
						confidence = ResolutionConfidence.INFERRED_ASSETS;
					}
				}

				// Use enhanced storage with Phase 1 metadata
				LoadUrlDB.storeIntentDetailsEnhanced(appAnalyzer, className, extractedContent,
													   confidence, dynamicPatterns, partialInfo);
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
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract iget-object field value");
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
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract move-result-object value");
			}

			// Case 4: sget-object - static field access
			else if (line.contains(targetRegister) && line.contains("sget-object")) {
				String staticValue = extractStaticFieldValue(fileContentArray, line);
				if (staticValue != null && !staticValue.isEmpty()) {
					return staticValue;
				}
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract sget-object field value");
			}

			// Case 5: move-object - register copy (recursive)
			else if (line.contains(targetRegister) && line.contains("move-object")) {
				String copiedRegister = extractCopiedRegister(line, targetRegister);
				if (copiedRegister != null && !copiedRegister.isEmpty()) {
					System.out.println("[DEBUG] LoadURL: Following move-object: " + targetRegister + " <- " + copiedRegister);
					// Recursively trace the source register
					return performBackwardSlicing(fileContentArray, index, copiedRegister);
				}
			}

			// Case 6: invoke-static Return Values (e.g., String.format())
			else if (line.contains(targetRegister) && line.contains("invoke-static")) {
				String staticInvokeResult = traceStaticInvokeReturn(fileContentArray, index, line, targetRegister);
				if (staticInvokeResult != null && !staticInvokeResult.isEmpty()) {
					System.out.println("[DEBUG] LoadURL: Found invoke-static result: " + staticInvokeResult);
					return staticInvokeResult;
				}
			}

			// Case 7: invoke-virtual with return value assignment
			else if (line.contains(targetRegister) && line.contains("invoke-virtual")) {
				String virtualInvokeResult = traceVirtualInvokeReturn(fileContentArray, index, line, targetRegister);
				if (virtualInvokeResult != null && !virtualInvokeResult.isEmpty()) {
					System.out.println("[DEBUG] LoadURL: Found invoke-virtual result: " + virtualInvokeResult);
					return virtualInvokeResult;
				}
			}

			// Case 8: aget/aget-object - array element access
			else if (line.contains(targetRegister) && (line.contains("aget-object") || line.contains("aget "))) {
				System.out.println("[DEBUG] LoadURL: Found aget/aget-object: " + line);
				String arrayResult = traceArrayAccess(fileContentArray, index, line, targetRegister);
				if (arrayResult != null && !arrayResult.isEmpty()) {
					return arrayResult;
				}
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract array element value");
			}

			// Case 9: new-instance with constructor chain
			else if (line.contains(targetRegister) && line.contains("new-instance")) {
				System.out.println("[DEBUG] LoadURL: Found new-instance: " + line);
				String newInstanceResult = traceNewInstance(fileContentArray, index, line, targetRegister);
				if (newInstanceResult != null && !newInstanceResult.isEmpty()) {
					return newInstanceResult;
				}
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract new-instance value");
			}

			// Case 10: filled-new-array - array initialization
			else if (line.contains(targetRegister) && line.contains("filled-new-array")) {
				System.out.println("[DEBUG] LoadURL: Found filled-new-array: " + line);
				String filledArrayResult = traceFilledNewArray(fileContentArray, index, line);
				if (filledArrayResult != null && !filledArrayResult.isEmpty()) {
					return filledArrayResult;
				}
				// Don't return fallback label - extraction failed
				System.out.println("[DEBUG] LoadURL: Could not extract filled-new-array value");
			}

			// Case 11: Try advanced string concatenation analysis
			String concatResult = StringOptimizer.handleStringConcatenation(fileContentArray, index, targetRegister);
			if (concatResult != null && !concatResult.isEmpty()) {
				// Return the actual value, not the metadata label
				System.out.println("[DEBUG] LoadURL: Found concatenated string: \"" + concatResult + "\"");
				return concatResult;
			}

			// Case 12: Try string manipulation chain analysis
			String chainResult = StringOptimizer.analyzeStringManipulationChain(fileContentArray, index, targetRegister);
			if (chainResult != null && !chainResult.isEmpty()) {
				// Return the actual value, not the metadata label
				System.out.println("[DEBUG] LoadURL: Found string manipulation result: \"" + chainResult + "\"");
				return chainResult;
			}

			// Case 13: Try comprehensive string analysis
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

			// Strategy 1: Try caller resolution
			String fromCaller = resolveFromCallersForString(fileContentArray, methodSig, targetRegister);
			if (!fromCaller.isEmpty() && !fromCaller.startsWith("UNRESOLVED")) {
				System.out.println("[DEBUG] LoadURL: Resolved from caller (Strategy 1): \"" + fromCaller + "\"");
				return fromCaller;
			}

			// Strategy 2: Look for field assignments related to this parameter
			String fromField = traceParameterToField(fileContentArray, currentIndex, methodSig, targetRegister);
			if (!fromField.isEmpty() && !fromField.startsWith("UNRESOLVED")) {
				System.out.println("[DEBUG] LoadURL: Resolved from field (Strategy 2): \"" + fromField + "\"");
				return fromField;
			}

			// Strategy 3: Look for this parameter being used in method calls that return string
			String fromMethodResult = traceParameterThroughMethods(fileContentArray, currentIndex, methodSig, targetRegister);
			if (!fromMethodResult.isEmpty() && !fromMethodResult.startsWith("UNRESOLVED")) {
				System.out.println("[DEBUG] LoadURL: Resolved through method (Strategy 3): \"" + fromMethodResult + "\"");
				return fromMethodResult;
			}

			// If all strategies fail, return detailed unresolved info
			System.out.println("[DEBUG] LoadURL: Could not resolve parameter from any strategy, returning UNRESOLVED_PARAM");
			return "UNRESOLVED_PARAM: " + targetRegister + " type=String method=" + methodSig;
		}

		System.out.println("[DEBUG] LoadURL: Exhausted all options, returning UNRESOLVED_LOADURL");
		return "UNRESOLVED_LOADURL: register=" + targetRegister
				+ " method=" + getEnclosingMethodSignature(fileContentArray, currentIndex)
				+ " line=" + currentIndex;
	}

	private static String traceStaticInvokeReturn(String[] fileContentArray, int index, String line, String targetRegister) {
		// Format: invoke-static {params}, Ljava/lang/String;->format(...)
		int methodArrowPos = line.indexOf(";->");
		if (methodArrowPos < 0) {
			return "";
		}

		// Look for common static methods that return strings
		if (line.contains("String;->format")) {
			return "String.format(...)";
		}
		if (line.contains("Integer;->toString") || line.contains("Long;->toString")) {
			return "Number.toString()";
		}
		if (line.contains("Boolean;->toString")) {
			return "Boolean.toString()";
		}

		return "";
	}

	private static String traceVirtualInvokeReturn(String[] fileContentArray, int index, String line, String targetRegister) {
		// Format: invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
		int methodArrowPos = line.indexOf(";->");
		if (methodArrowPos < 0) {
			return "";
		}

		// Look for StringBuilder.toString()
		if (line.contains("StringBuilder;->toString")) {
			// Check if the previous instruction was append
			if (index > 0) {
				String prevLine = fileContentArray[index - 1];
				if (prevLine.contains("append") && prevLine.contains("StringBuilder")) {
					return StringOptimizer.string_builder(fileContentArray, index - 1);
				}
			}
			return "StringBuilder.toString()";
		}

		if (line.contains("String;->concat")) {
			return "String.concat(...)";
		}

		if (line.contains("String;->substring")) {
			return "String.substring(...)";
		}

		return "";
	}

	private static String traceArrayAccess(String[] fileContentArray, int index, String line, String targetRegister) {
		// Format: aget-object vDest, vArray, vIndex
		// Try to trace what's in the array
		String[] parts = line.split(",");
		if (parts.length < 2) {
			return "";
		}

		String arrayRegister = parts[1].trim();

		// Look for where the array was initialized
		for (int i = index - 1; i >= Math.max(0, index - 20); i--) {
			String searchLine = fileContentArray[i];
			if (searchLine == null) continue;

			// Look for filled-new-array or new-array
			if (searchLine.contains(arrayRegister) && searchLine.contains("filled-new-array")) {
				return traceFilledNewArray(fileContentArray, i, searchLine);
			}
		}

		return "";
	}

	private static String traceNewInstance(String[] fileContentArray, int index, String line, String targetRegister) {
		// Format: new-instance vDest, Ljava/lang/StringBuilder;
		// Look ahead for invoke-direct <init>
		for (int i = index + 1; i < Math.min(fileContentArray.length, index + 10); i++) {
			String nextLine = fileContentArray[i];
			if (nextLine == null) continue;

			if (nextLine.contains(targetRegister) && nextLine.contains("invoke-direct") && nextLine.contains("<init>")) {
				System.out.println("[DEBUG] LoadURL: Found constructor call for new-instance: " + nextLine);

				// If it's StringBuilder, check for subsequent append calls
				if (line.contains("StringBuilder")) {
					return StringOptimizer.string_builder(fileContentArray, i);
				}
				return "Constructor: " + line;
			}
		}

		return "";
	}

	private static String traceFilledNewArray(String[] fileContentArray, int index, String line) {
		// Format: filled-new-array {v0, v1, v2}, [Ljava/lang/String;
		// Extract the array elements and try to resolve them
		int startBrace = line.indexOf('{');
		int endBrace = line.indexOf('}');

		if (startBrace < 0 || endBrace < 0) {
			return "";
		}

		String registerStr = line.substring(startBrace + 1, endBrace);
		String[] registers = registerStr.split(",");

		System.out.println("[DEBUG] LoadURL: Tracing filled-new-array with " + registers.length + " elements");

		// Try to resolve the first element (most likely to be the URL/JS string)
		if (registers.length > 0) {
			String firstElementReg = registers[0].trim();
			String resolvedElement = performBackwardSlicing(fileContentArray, index, firstElementReg);
			if (resolvedElement != null && !resolvedElement.isEmpty() && !resolvedElement.startsWith("UNRESOLVED")) {
				return resolvedElement;
			}
		}

		return "";
	}

	private static String resolveFromCallersForString(String[] fileContentArray, String methodLine, String paramRegister) {
		return resolveFromCallersForStringWithContext(fileContentArray, methodLine, paramRegister, new java.util.HashSet<>());
	}

	private static String resolveFromCallersForStringWithContext(String[] fileContentArray, String methodLine, String paramRegister, java.util.Set<String> visitedMethods) {
		String descriptor = extractMethodDescriptor(methodLine);
		if (descriptor.isEmpty()) {
			System.out.println("[DEBUG] LoadURL: Could not extract method descriptor from: " + methodLine);
			return "";
		}

		// Check if we're already visiting this method (prevent infinite recursion)
		if (visitedMethods.contains(descriptor)) {
			System.out.println("[DEBUG] LoadURL: Already visiting method: " + descriptor + " (cycle detected)");
			return "";
		}
		visitedMethods.add(descriptor);

		System.out.println("[DEBUG] LoadURL: Looking for callers of method: " + descriptor);

		int paramIndex = getParamIndexFromRegister(methodLine, paramRegister);
		if (paramIndex < 0) {
			System.out.println("[DEBUG] LoadURL: Could not get param index for register: " + paramRegister);
			return "";
		}
		System.out.println("[DEBUG] LoadURL: Parameter " + paramRegister + " maps to argument index: " + paramIndex);

		int methodStart = -1;
		int callersFound = 0;
		int maxCallers = 10; // Increased from 5 to check more callers

		for (int i = 0; i < fileContentArray.length && callersFound < maxCallers; i++) {
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
					String resolved = traceStringInMethodWithContext(fileContentArray, i, methodStart, argReg, visitedMethods);
					if (!resolved.isEmpty() && !resolved.startsWith("UNRESOLVED")) {
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
		return traceStringInMethodWithContext(fileContentArray, index, methodStart, register, new java.util.HashSet<>());
	}

	private static String traceStringInMethodWithContext(String[] fileContentArray, int index, int methodStart, String register, java.util.Set<String> visitedMethods) {
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
			String nestedResolved = resolveFromCallersForStringWithContext(fileContentArray, callerMethodSig, currentRegister, visitedMethods);
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
		// Filter out known log/error messages that aren't useful data
		if (value.contains("Ignore addJavascriptInterface due to low Android version")) {
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
		// Split by whitespace, being careful with regex special characters
		try {
			String[] parts = methodLine.split("\\s+");
			return parts.length > 0 ? parts[parts.length - 1].trim() : "";
		} catch (Exception e) {
			// If split fails, manually extract method descriptor
			int openParen = methodLine.indexOf("(");
			int closeParen = methodLine.lastIndexOf(")");
			if (openParen >= 0 && closeParen > openParen) {
				return methodLine.substring(openParen, closeParen + 1);
			}
			return "";
		}
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

	/**
	 * Finds all callers of a specific method in the code.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param targetMethodSig Method signature to find callers for (e.g., "loadUrl(Ljava/lang/String;)V")
	 * @return Array of invoke lines that call this method
	 */
	private static String[] findAllCallers(String[] fileContentArray, String targetMethodSig) {
		java.util.List<String> callers = new java.util.ArrayList<>();

		// Extract method name from signature
		String methodName = targetMethodSig.substring(targetMethodSig.indexOf(" ") + 1);
		methodName = methodName.substring(0, methodName.indexOf("("));

		System.out.println("[DEBUG] LoadURL: Looking for callers of method: " + methodName);

		for (String line : fileContentArray) {
			if (line != null && line.contains("invoke") && line.contains("->" + methodName)) {
				callers.add(line);
				System.out.println("[DEBUG] LoadURL: Found potential caller: " + line.trim());
			}
		}

		return callers.toArray(new String[0]);
	}

	/**
	 * Finds the index of a line in the file content array.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param targetLine The line to find
	 * @return Index of the line, or -1 if not found
	 */
	private static int findLineIndex(String[] fileContentArray, String targetLine) {
		for (int i = 0; i < fileContentArray.length; i++) {
			if (fileContentArray[i] != null && fileContentArray[i].equals(targetLine)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Finds the start of the method containing the given line index.
	 *
	 * @param fileContentArray Array of Smali code lines
	 * @param index Index within the method
	 * @return Index of the .method line, or -1 if not found
	 */
	private static int findMethodStart(String[] fileContentArray, int index) {
		for (int i = index; i >= 0; i--) {
			String line = fileContentArray[i];
			if (line != null && line.contains(METHOD_MARKER)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Tries to resolve a parameter by tracing it to field assignments.
	 * Looks for iput-object or similar that stores the parameter to a field.
	 */
	private static String traceParameterToField(String[] fileContentArray, int index, String methodSig, String paramRegister) {
		System.out.println("[DEBUG] LoadURL: Strategy 2 - Looking for field assignments with " + paramRegister);

		// Find the method start
		int methodStart = findMethodStart(fileContentArray, index);
		if (methodStart < 0) return "";

		// Look for iput/iput-object with this parameter
		for (int i = methodStart; i < index; i++) {
			String line = fileContentArray[i];
			if (line != null && line.contains("iput") && line.contains(paramRegister)) {
				System.out.println("[DEBUG] LoadURL: Found field assignment with " + paramRegister + ": " + line.trim());
				// Try to extract field type/value
				String fieldInfo = extractFieldFromIput(line);
				if (!fieldInfo.isEmpty()) {
					return fieldInfo;
				}
			}
		}

		return "";
	}

	/**
	 * Tries to resolve a parameter by tracing it through method calls.
	 * Looks for method calls that take this parameter and return string.
	 */
	private static String traceParameterThroughMethods(String[] fileContentArray, int index, String methodSig, String paramRegister) {
		System.out.println("[DEBUG] LoadURL: Strategy 3 - Looking for method calls using " + paramRegister);

		// Find the method start
		int methodStart = findMethodStart(fileContentArray, index);
		if (methodStart < 0) return "";

		// Look for invoke instructions that use this parameter
		for (int i = methodStart; i < index; i++) {
			String line = fileContentArray[i];
			if (line != null && line.contains("invoke") && line.contains(paramRegister)) {
				System.out.println("[DEBUG] LoadURL: Found invoke using " + paramRegister + ": " + line.trim());

				// Extract the return value register
				String returnReg = extractReturnRegisterFromInvoke(line);
				if (!returnReg.isEmpty()) {
					// Try to trace what's returned
					String returnValue = performBackwardSlicing(fileContentArray, i, returnReg);
					if (!returnValue.isEmpty() && !returnValue.startsWith("UNRESOLVED")) {
						return returnValue;
					}
				}
			}
		}

		return "";
	}

	/**
	 * Extracts field information from an iput instruction.
	 */
	private static String extractFieldFromIput(String line) {
		try {
			// Format: iput-object p1, p0, Lcom/example/Class;->fieldName:Ljava/lang/String;
			int fieldStart = line.lastIndexOf("Lcom");
			if (fieldStart < 0) fieldStart = line.lastIndexOf("Landroid");

			if (fieldStart >= 0) {
				int fieldEnd = line.length();
				String fieldRef = line.substring(fieldStart, fieldEnd).trim();
				System.out.println("[DEBUG] LoadURL: Extracted field reference: " + fieldRef);
				return fieldRef;
			}
		} catch (Exception e) {
			// Silent failure
		}
		return "";
	}

	/**
	 * Extracts the return register from an invoke instruction.
	 */
	private static String extractReturnRegisterFromInvoke(String line) {
		try {
			// The return value is typically in the first register of the invoke
			int braceStart = line.indexOf('{');
			int braceEnd = line.indexOf('}');

			if (braceStart >= 0 && braceEnd > braceStart) {
				String regs = line.substring(braceStart + 1, braceEnd).trim();
				String[] parts = regs.split(",");
				if (parts.length > 0) {
					return parts[0].trim();
				}
			}
		} catch (Exception e) {
			// Silent failure
		}
		return "";
	}

	/**
	 * PHASE 1 - Trick 2: Detects dynamic patterns that make value resolution unreliable
	 * Looks for patterns like STRING_CONCAT, METHOD_RETURN, etc.
	 */
	private static String detectDynamicPatterns(String[] fileArray, int index) {
		StringBuilder patterns = new StringBuilder();

		// Look at the loadUrl line and surrounding context
		for (int i = Math.max(0, index - 20); i < index; i++) {
			String line = fileArray[i];
			if (line == null || line.isEmpty()) continue;

			// Pattern 1: String concatenation
			if (line.contains("String;->concat")) {
				patterns.append("STRING_CONCAT|");
			}

			// Pattern 2: String formatting
			if (line.contains("String;->format") || line.contains("String;->replace")) {
				patterns.append("STRING_FORMAT|");
			}

			// Pattern 3: Method returns (we don't have implementation)
			if ((line.contains("invoke-virtual") || line.contains("invoke-static"))
				&& line.contains(")Ljava/lang/String;")) {
				patterns.append("METHOD_RETURN|");
			}

			// Pattern 4: StringBuilder (creates string at runtime)
			if (line.contains("StringBuilder") || line.contains("StringBuffer")) {
				patterns.append("STRING_BUILDER|");
			}
		}

		// Remove trailing pipe if present
		if (patterns.length() > 0 && patterns.charAt(patterns.length() - 1) == '|') {
			patterns.deleteCharAt(patterns.length() - 1);
		}

		return patterns.toString();
	}

	/**
	 * PHASE 1 - Trick 3: Partial Information Extraction
	 * When full resolution fails, extract all available information
	 */
	private static LoadUrlDB.PartialInfo extractPartialInfo(
			String[] fileArray, int index, int methodStart) {

		LoadUrlDB.PartialInfo partial = new LoadUrlDB.PartialInfo();
		StringBuilder hints = new StringBuilder();

		// Extract method name
		for (int i = methodStart; i < index; i++) {
			if (fileArray[i] != null && fileArray[i].contains(".method")) {
				partial.methodName = extractMethodNameFromSignature(fileArray[i]);
				hints.append("method_found|");
				break;
			}
		}

		// Extract class name
		if (index > 0) {
			partial.className = getEnclosingClassName(fileArray, index);
			if (partial.className != null && !partial.className.isEmpty()) {
				hints.append("class_found|");
			}
		}

		// Try to infer source from class name patterns
		if (partial.className != null) {
			String lower = partial.className.toLowerCase();
			if (lower.contains("network") || lower.contains("http") || lower.contains("request")) {
				partial.sourceHint = "NETWORK";
				hints.append("network_hint|");
			} else if (lower.contains("file") || lower.contains("asset") || lower.contains("storage")) {
				partial.sourceHint = "FILE";
				hints.append("file_hint|");
			} else if (lower.contains("config") || lower.contains("constant")) {
				partial.sourceHint = "CONST";
				hints.append("const_hint|");
			}
		}

		// Extract parameter type from method signature
		if (partial.methodName != null) {
			if (partial.methodName.contains("(Ljava/lang/String;)")) {
				partial.parameterType = "String";
				hints.append("type_string|");
			}
		}

		partial.hints = hints.toString();
		if (!partial.hints.isEmpty() && partial.hints.charAt(partial.hints.length() - 1) == '|') {
			partial.hints = partial.hints.substring(0, partial.hints.length() - 1);
		}

		return partial;
	}

	/**
	 * PHASE 1 - Trick 4: Asset File Inference
	 * Check if unresolved URL might match an actual asset file in the APK
	 */
	private static String inferFromAssets(ApplicationAnalysis appAnalyzer) {
		String appName = appAnalyzer.getAppDetails().getAppName();
		String assetsPath = "output/intermediate/" + appName + "/assets/";
		java.io.File assetsDir = new java.io.File(assetsPath);

		if (!assetsDir.exists() || !assetsDir.isDirectory()) {
			return "";
		}

		StringBuilder inferredUrls = new StringBuilder();

		// Common HTML/JS files
		String[] commonFiles = {
			"index.html",
			"www/index.html",
			"app.html",
			"main.html",
			"index.htm"
		};

		for (String file : commonFiles) {
			java.io.File f = new java.io.File(assetsDir, file);
			if (f.exists()) {
				if (inferredUrls.length() > 0) {
					inferredUrls.append(" | ");
				}
				inferredUrls.append("file:///android_asset/").append(file);
			}
		}

		return inferredUrls.toString();
	}

	/**
	 * Extracts method name from a method signature line
	 */
	private static String extractMethodNameFromSignature(String methodLine) {
		if (methodLine == null || !methodLine.contains(".method")) {
			return "";
		}

		try {
			// Format: ".method ... methodName(...)..."
			int methodIndex = methodLine.indexOf(".method");
			int parenIndex = methodLine.indexOf("(", methodIndex);

			if (methodIndex >= 0 && parenIndex > methodIndex) {
				String middle = methodLine.substring(methodIndex + 7, parenIndex).trim();
				// Remove access modifiers
				String[] tokens = middle.split("\\s+");
				return tokens[tokens.length - 1];
			}
		} catch (Exception e) {
			// Silent failure
		}
		return "";
	}

	/**
	 * Gets the enclosing class name for a given index in the file
	 */
	private static String getEnclosingClassName(String[] fileArray, int index) {
		for (int i = index; i >= 0; i--) {
			if (fileArray[i] != null && fileArray[i].contains(".class")) {
				return extractClassName(fileArray[i]);
			}
		}
		return "";
	}

	private static void debug(String message) {
		if (GenericConstants.DEBUG) {
			System.out.println(message);
		}
	}
}
