/**
 * 
 */
package de.potsdam.loadurl;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.potsdam.constants.GenericConstants;

/**
 * @author abhishektiwari
 *
 * Optimizes and reconstructs string values from Smali bytecode.
 * Handles various string operations including:
 * - StringBuilder append operations
 * - String concatenation
 * - String formatting and manipulation
 * - Multi-step string construction
 */
public class StringOptimizer {
	
	private static final String METHOD_MARKER = ".method";
	private static final String APPEND_PATTERN = "append";
	private static final String CONST_STRING = "const-string";
	private static final String CONST_STRING_RANGE = "const-string/jumbo";

	/**
	 * Reconstructs a string value from StringBuilder operations in Smali code.
	 * Traces backward from a StringBuilder.toString() call to find all append operations
	 * and reconstructs the final string in correct order.
	 *
	 * @param array Array of Smali code lines
	 * @param index Index of the StringBuilder.toString() instruction
	 * @return Reconstructed string value or error message
	 */
	public static String string_builder(String[] array, int index) {
		if (array == null || index < 0 || index >= array.length) {
			return "Invalid index for string_builder analysis";
		}

		if (!array[index].contains(GenericConstants.STRINGBUILDER_TOSTRING)) {
			return "Not a StringBuilder.toString() instruction";
		}

		try {
			// Extract the StringBuilder register being converted
			String builderRegister = extractBuilderRegister(array[index]);
			if (builderRegister == null || builderRegister.isEmpty()) {
				return "Could not extract StringBuilder register";
			}

			// Reconstruct string from append operations
			return reconstructStringFromAppends(array, index, builderRegister);

		} catch (Exception e) {
			return "Error reconstructing StringBuilder string: " + e.getMessage();
		}
	}

	/**
	 * Handles string concatenation patterns in Smali code.
	 * Supports patterns like "str1" + "str2" or register concatenation.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index in the array
	 * @param register Target register to trace
	 * @return Concatenated string value
	 */
	public static String handleStringConcatenation(String[] array, int index, String register) {
		if (array == null || index < 0 || index >= array.length) {
			return null;
		}

		try {
			StringBuilder result = new StringBuilder();
			List<String> parts = new ArrayList<>();

			// Walk backward to find all string parts being concatenated
			int searchIndex = index - 1;
			while (searchIndex >= 0 && !array[searchIndex].contains(METHOD_MARKER)) {
				String line = array[searchIndex];

				// Look for const-string operations
				if (line.contains(register) && line.contains(CONST_STRING)) {
					String value = extractConstStringValue(line);
					if (value != null) {
						parts.add(0, value);
					}
				}

				// Look for move operations (register copies)
				else if (line.contains("move") && line.contains(register)) {
					String sourceRegister = extractSourceRegister(line, register);
					if (sourceRegister != null && !sourceRegister.startsWith("p")) {
						return handleStringConcatenation(array, searchIndex, sourceRegister);
					}
				}

				searchIndex--;
			}

			// Combine all parts
			for (String part : parts) {
				result.append(part);
			}

			return result.length() > 0 ? result.toString() : null;

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Handles string manipulation operations like toUpperCase, toLowerCase, trim, etc.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param methodName The string method being called
	 * @return Information about the operation
	 */
	public static String handleStringManipulation(String[] array, int index, String methodName) {
		if (array == null || methodName == null) {
			return null;
		}

		try {
			String operation = "";

			// Map common string operations
			if (methodName.contains("toUpperCase")) {
				operation = "uppercase";
			} else if (methodName.contains("toLowerCase")) {
				operation = "lowercase";
			} else if (methodName.contains("trim")) {
				operation = "trimmed";
			} else if (methodName.contains("replace")) {
				operation = "replaced";
			} else if (methodName.contains("substring")) {
				operation = "substring";
			} else if (methodName.contains("split")) {
				operation = "split";
			} else if (methodName.contains("concat")) {
				operation = "concatenated";
			}

			return "String operation: " + operation;

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Extracts the StringBuilder register from a toString() instruction.
	 * Format: "invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;"
	 *
	 * @param line The toString instruction line
	 * @return The StringBuilder register (e.g., "v0") or null
	 */
	private static String extractBuilderRegister(String line) {
		try {
			int braceStart = line.indexOf('{');
			int braceEnd = line.indexOf('}');

			if (braceStart != -1 && braceEnd != -1) {
				String registerStr = line.substring(braceStart + 1, braceEnd).trim();
				if (!registerStr.isEmpty()) {
					return registerStr;
				}
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Extracts the source register from a move instruction.
	 * Format: "move-object v0, v1" -> returns "v1" when register is "v0"
	 *
	 * @param line The move instruction
	 * @param targetRegister The register we're looking for
	 * @return The source register or null
	 */
	private static String extractSourceRegister(String line, String targetRegister) {
		try {
			String[] parts = line.split(",");
			if (parts.length >= 2) {
				// Check if first register is our target
				String[] firstParts = parts[0].split("\\s+");
				String firstReg = firstParts[firstParts.length - 1].trim();

				if (firstReg.equals(targetRegister)) {
					return parts[1].trim();
				}
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Reconstructs a string by walking backward through all StringBuilder.append() calls.
	 *
	 * @param array Array of Smali code lines
	 * @param currentIndex Current index (at toString call)
	 * @param builderRegister The StringBuilder register
	 * @return Reconstructed string
	 */
	private static String reconstructStringFromAppends(String[] array, int currentIndex, String builderRegister) {
		List<String> appendValues = new ArrayList<>();
		int index = currentIndex - 1;

		while (index >= 0 && !array[index].contains(METHOD_MARKER)) {
			String line = array[index];

			// Look for append operations on this StringBuilder
			if (line.contains(builderRegister) && line.contains(APPEND_PATTERN) &&
			    line.contains(GenericConstants.STRINGBUILDER_APPEND)) {

				// Extract the argument register being appended
				String argRegister = extractAppendArgumentRegister(line);

				if (argRegister != null && !argRegister.isEmpty()) {
					// Get the value of this register
					String value = traceRegisterValue(array, index, argRegister);
					if (value != null && !value.isEmpty()) {
						appendValues.add(0, value);  // Insert at beginning to maintain order
					} else if (argRegister.startsWith("p")) {
						appendValues.add(0, "[Parameter: " + argRegister + "]");
					}
				}
			}

			// Look for StringBuilder initialization
			if (line.contains(builderRegister) && line.contains("new-instance")) {
				break;  // Found the initialization, stop here
			}

			index--;
		}

		// Combine all appended values
		StringBuilder result = new StringBuilder();
		for (String value : appendValues) {
			result.append(value);
		}

		return result.length() > 0 ? result.toString() : "Empty StringBuilder";
	}

	/**
	 * Extracts the register being appended from an append instruction.
	 * Format: "invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(...)V"
	 * Returns v1 (the argument being appended)
	 *
	 * @param line The append instruction
	 * @return The argument register or null
	 */
	private static String extractAppendArgumentRegister(String line) {
		try {
			int braceStart = line.indexOf('{');
			int braceEnd = line.indexOf('}');

			if (braceStart != -1 && braceEnd != -1) {
				String registerStr = line.substring(braceStart + 1, braceEnd).trim();
				String[] registers = registerStr.split(",");

				// The argument being appended is typically the second register
				if (registers.length > 1) {
					return registers[1].trim();
				} else if (registers.length == 1) {
					return registers[0].trim();
				}
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Traces the value of a register by walking backward through the code.
	 * Handles const-string, iget-object, and other assignment patterns.
	 *
	 * @param array Array of Smali code lines
	 * @param currentIndex Current search position
	 * @param targetRegister The register to find
	 * @return The register's value or null
	 */
	private static String traceRegisterValue(String[] array, int currentIndex, String targetRegister) {
		int searchIndex = currentIndex - 1;

		while (searchIndex >= 0 && !array[searchIndex].contains(METHOD_MARKER)) {
			String line = array[searchIndex];

			// Case 1: const-string assignment
			if (line.contains(targetRegister) && line.contains(CONST_STRING)) {
				return extractConstStringValue(line);
			}

			// Case 2: move-object assignment
			else if (line.contains("move-object") && line.contains(targetRegister)) {
				String sourceReg = extractSourceRegister(line, targetRegister);
				if (sourceReg != null && !sourceReg.isEmpty()) {
					return traceRegisterValue(array, searchIndex, sourceReg);
				}
			}

			// Case 3: iget-object (field access)
			else if (line.contains("iget-object") && line.contains(targetRegister)) {
				String fieldInfo = extractFieldInfo(line);
				if (fieldInfo != null) {
					return "[Field: " + fieldInfo + "]";
				}
			}

			// Case 4: invoke-static or invoke-virtual result
			else if (line.contains("invoke-") && line.contains(targetRegister)) {
				String methodInfo = extractMethodInfo(line);
				if (methodInfo != null) {
					return "[Method: " + methodInfo + "]";
				}
			}

			// Case 5: Parameter register
			if (targetRegister.startsWith("p")) {
				return null;  // Cannot determine parameter value statically
			}

			searchIndex--;
		}

		return null;
	}

	/**
	 * Extracts a string value from a const-string instruction.
	 * Format: "const-string v0, \"Hello World\""
	 *
	 * @param line The const-string instruction
	 * @return The string value or null
	 */
	private static String extractConstStringValue(String line) {
		try {
			int firstQuote = line.indexOf('"');
			int lastQuote = line.lastIndexOf('"');

			if (firstQuote != -1 && lastQuote != -1 && firstQuote < lastQuote) {
				return line.substring(firstQuote + 1, lastQuote);
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Extracts field information from an iget-object instruction.
	 * Format: "iget-object v0, v1, Lclass;->field:Ltype;"
	 *
	 * @param line The iget-object instruction
	 * @return Field description or null
	 */
	private static String extractFieldInfo(String line) {
		try {
			int lastArrow = line.lastIndexOf("->");
			int lastSemicolon = line.lastIndexOf(";");

			if (lastArrow != -1 && lastSemicolon != -1 && lastArrow < lastSemicolon) {
				return line.substring(lastArrow + 2, lastSemicolon + 1);
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Extracts method information from an invoke instruction.
	 *
	 * @param line The invoke instruction
	 * @return Method name or null
	 */
	private static String extractMethodInfo(String line) {
		try {
			int methodStart = line.indexOf("->");
			int methodEnd = line.indexOf("(");

			if (methodStart != -1 && methodEnd != -1 && methodStart < methodEnd) {
				return line.substring(methodStart + 2, methodEnd);
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Handles string literal values that may contain escape sequences.
	 * Converts Smali escape sequences to Java string format.
	 *
	 * @param smaliString String value from Smali code
	 * @return Decoded string value
	 */
	public static String decodeStringValue(String smaliString) {
		if (smaliString == null) {
			return null;
		}

		try {
			// Handle common escape sequences
			String decoded = smaliString
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace("\\\"", "\"")
				.replace("\\\\", "\\");

			return decoded;
		} catch (Exception e) {
			return smaliString;
		}
	}


	/**
	 * Handles string formatting operations (String.format, etc.)
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param formatStringRegister The register containing format string
	 * @param argsStartIndex Index where format arguments begin
	 * @return Formatted string information
	 */
	public static String handleStringFormatting(String[] array, int index, String formatStringRegister, int argsStartIndex) {
		try {
			String formatString = traceRegisterValue(array, index, formatStringRegister);
			if (formatString != null && !formatString.isEmpty()) {
				return "Formatted string: " + formatString + " (with arguments)";
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Handles complex multi-step string construction involving multiple operations.
	 * Traces through multiple StringBuilder operations, method calls, and transformations.
	 *
	 * @param array Array of Smali code lines
	 * @param startIndex Starting index for analysis
	 * @param targetRegister Register containing final string
	 * @return Reconstructed string with operation history
	 */
	public static String handleComplexStringConstruction(String[] array, int startIndex, String targetRegister) {
		if (array == null || startIndex < 0 || startIndex >= array.length) {
			return null;
		}

		try {
			StringBuilder result = new StringBuilder();
			List<String> operations = new ArrayList<>();
			int index = startIndex - 1;

			while (index >= 0 && !array[index].contains(METHOD_MARKER)) {
				String line = array[index];

				// Track StringBuilder operations
				if (line.contains(targetRegister)) {
					if (line.contains("new-instance")) {
						operations.add(0, "StringBuilder initialized");
					} else if (line.contains("append")) {
						String argReg = extractAppendArgumentRegister(line);
						if (argReg != null) {
							String value = traceRegisterValue(array, index, argReg);
							if (value != null && !value.isEmpty()) {
								operations.add(0, "appended: " + value);
								result.insert(0, value);
							}
						}
					} else if (line.contains("toString")) {
						operations.add("toString() called");
					}
				}

				index--;
			}

			return result.length() > 0 ? result.toString() : null;

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Handles conditional string selection (if-else string assignments).
	 * Traces which string value is assigned based on conditions.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param register Register to trace
	 * @return Possible string values
	 */
	public static List<String> handleConditionalStrings(String[] array, int index, String register) {
		List<String> possibleValues = new ArrayList<>();

		if (array == null || index < 0) {
			return possibleValues;
		}

		try {
			int searchIndex = index - 1;
			int ifCount = 0;

			while (searchIndex >= 0 && !array[searchIndex].contains(METHOD_MARKER)) {
				String line = array[searchIndex];

				// Track if-else branches
				if (line.contains("if-")) {
					ifCount++;
				}

				// Find string assignments in different branches
				if (line.contains(register) && line.contains(CONST_STRING)) {
					String value = extractConstStringValue(line);
					if (value != null && !possibleValues.contains(value)) {
						possibleValues.add(value);
					}
				}

				searchIndex--;
			}
		} catch (Exception e) {
			// Error handling
		}

		return possibleValues;
	}

	/**
	 * Analyzes string manipulation chains (e.g., str.toUpperCase().trim().replace()).
	 * Traces through multiple method calls on a string.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param initialStringRegister Register containing initial string
	 * @return Description of the manipulation chain
	 */
	public static String analyzeStringManipulationChain(String[] array, int index, String initialStringRegister) {
		if (array == null || index < 0) {
			return null;
		}

		try {
			List<String> operations = new ArrayList<>();
			String baseString = traceRegisterValue(array, index, initialStringRegister);

			if (baseString == null || baseString.isEmpty()) {
				return null;
			}

			operations.add("Base: " + baseString);

			// Look for method calls on this string
			int searchIndex = index - 1;
			while (searchIndex >= 0 && !array[searchIndex].contains(METHOD_MARKER) && operations.size() < 10) {
				String line = array[searchIndex];

				if (line.contains("invoke-virtual") || line.contains("invoke-static")) {
					String methodName = extractMethodNameFromInvoke(line);
					if (methodName != null && isStringOperation(methodName)) {
						operations.add("→ " + methodName + "()");
					}
				}

				searchIndex--;
			}

			// Build result description
			StringBuilder result = new StringBuilder();
			for (String op : operations) {
				result.append(op).append(" ");
			}

			return result.toString().trim();

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Extracts method name from an invoke instruction.
	 *
	 * @param invokeLine The invoke instruction
	 * @return Method name or null
	 */
	private static String extractMethodNameFromInvoke(String invokeLine) {
		try {
			int methodStart = invokeLine.indexOf("->");
			int methodEnd = invokeLine.indexOf("(");

			if (methodStart != -1 && methodEnd != -1) {
				return invokeLine.substring(methodStart + 2, methodEnd).trim();
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Checks if a method name represents a string operation.
	 *
	 * @param methodName The method name to check
	 * @return true if it's a string operation
	 */
	private static boolean isStringOperation(String methodName) {
		return methodName.matches(".*(toUpperCase|toLowerCase|trim|replace|substring|split|concat|format|valueOf).*");
	}

	/**
	 * Handles string array operations (joining array elements into a string).
	 * Traces array creation and joining operations.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param arrayRegister Register containing array
	 * @return Joined string representation
	 */
	public static String handleStringArrayOperations(String[] array, int index, String arrayRegister) {
		if (array == null || index < 0) {
			return null;
		}

		try {
			List<String> elements = new ArrayList<>();
			int searchIndex = index - 1;

			// Look for array element assignments
			while (searchIndex >= 0 && !array[searchIndex].contains(METHOD_MARKER)) {
				String line = array[searchIndex];

				// Find aput-object operations (array put)
				if (line.contains("aput-object") && line.contains(arrayRegister)) {
					String elementReg = extractArrayElementRegister(line);
					if (elementReg != null) {
						String elementValue = traceRegisterValue(array, searchIndex, elementReg);
						if (elementValue != null && !elementValue.isEmpty()) {
							elements.add(elementValue);
						}
					}
				}

				// Find array initialization
				if (line.contains("filled-new-array") && line.contains(arrayRegister)) {
					break;
				}

				searchIndex--;
			}

			if (elements.isEmpty()) {
				return null;
			}

			// Join with appropriate delimiter
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < elements.size(); i++) {
				result.append(elements.get(i));
				if (i < elements.size() - 1) {
					result.append(", ");
				}
			}

			return "[" + result.toString() + "]";

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Extracts the element register from an aput-object instruction.
	 *
	 * @param line The aput-object instruction
	 * @return The element register or null
	 */
	private static String extractArrayElementRegister(String line) {
		try {
			String[] parts = line.split(",");
			if (parts.length > 0) {
				// First register is usually the element
				String[] tokens = parts[0].split("\\s+");
				if (tokens.length > 0) {
					return tokens[tokens.length - 1].trim();
				}
			}
		} catch (Exception e) {
			// Error handling
		}
		return null;
	}

	/**
	 * Handles string encoding/decoding operations (Base64, URL encoding, etc.).
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param methodName The encoding/decoding method
	 * @param inputRegister Register containing input string
	 * @return Encoding operation description
	 */
	public static String handleStringEncoding(String[] array, int index, String methodName, String inputRegister) {
		try {
			String input = traceRegisterValue(array, index, inputRegister);

			if (input == null) {
				return null;
			}

			String operation = "unknown";
			if (methodName.contains("encodeBase64") || methodName.contains("encode")) {
				operation = "Base64 encoded";
			} else if (methodName.contains("decodeBase64") || methodName.contains("decode")) {
				operation = "Base64 decoded";
			} else if (methodName.contains("URLEncode")) {
				operation = "URL encoded";
			} else if (methodName.contains("URLDecode")) {
				operation = "URL decoded";
			}

			return input + " (" + operation + ")";

		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Provides comprehensive string analysis report combining multiple approaches.
	 *
	 * @param array Array of Smali code lines
	 * @param index Current index
	 * @param register Register to analyze
	 * @return Comprehensive analysis report
	 */
	public static String analyzeStringComprehensive(String[] array, int index, String register) {
		StringBuilder report = new StringBuilder();

		try {
			// Direct value
			String directValue = traceRegisterValue(array, index, register);
			if (directValue != null && !directValue.isEmpty()) {
				report.append("Direct Value: ").append(directValue).append("\n");
			}

			// Conditional branches
			List<String> conditionalValues = handleConditionalStrings(array, index, register);
			if (!conditionalValues.isEmpty()) {
				report.append("Possible Values: ").append(conditionalValues).append("\n");
			}

			// Manipulation chain
			String chainAnalysis = analyzeStringManipulationChain(array, index, register);
			if (chainAnalysis != null && !chainAnalysis.isEmpty()) {
				report.append("Manipulation Chain: ").append(chainAnalysis).append("\n");
			}

			return report.length() > 0 ? report.toString() : null;

		} catch (Exception e) {
			return null;
		}
	}
}
