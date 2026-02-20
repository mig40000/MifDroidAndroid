package de.potsdam.bridge;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.potsdam.SmaliContent.SmaliContent;
import de.potsdam.constants.GenericConstants;
import de.potsdam.db.LoadUrlDB;
import de.potsdam.main.ApplicationAnalysis;

public class JavascriptInterfaceAnalyzer {

    private static final String JS_INTERFACE_ANNOTATION = "Landroid/webkit/JavascriptInterface;";

    public static void extractAndStore(ApplicationAnalysis appAnalyzer) {
        SmaliContent smaliData = appAnalyzer.getSmaliData();
        Map<String, List<String>> annotatedMethods = collectAnnotatedMethods(smaliData.classContent);

        for (List<String> fileContentInSmaliFormat : smaliData.classContent) {
            String className = extractClassName(fileContentInSmaliFormat);
            if (className == null) {
                continue;
            }
            analyzeClass(fileContentInSmaliFormat, className, annotatedMethods, appAnalyzer);
        }
    }

    private static Map<String, List<String>> collectAnnotatedMethods(List<List<String>> allClasses) {
        Map<String, List<String>> result = new HashMap<>();

        for (List<String> classLines : allClasses) {
            String className = extractClassName(classLines);
            if (className == null) {
                continue;
            }

            String currentMethod = null;
            boolean inMethod = false;
            Set<String> methodSet = new HashSet<>();

            for (String line : classLines) {
                if (line.startsWith(".method ")) {
                    currentMethod = line.trim();
                    inMethod = true;
                } else if (line.startsWith(".end method")) {
                    currentMethod = null;
                    inMethod = false;
                } else if (inMethod && line.contains(JS_INTERFACE_ANNOTATION) && currentMethod != null) {
                    methodSet.add(currentMethod);
                }
            }

            if (!methodSet.isEmpty()) {
                result.put(className, new ArrayList<>(methodSet));
            }
        }

        return result;
    }

    private static void analyzeClass(List<String> classLines,
                                     String className,
                                     Map<String, List<String>> annotatedMethods,
                                     ApplicationAnalysis appAnalyzer) {
        String currentMethod = null;
        int methodStart = 0;

        for (int i = 0; i < classLines.size(); i++) {
            String line = classLines.get(i);

            if (line.startsWith(".method ")) {
                currentMethod = line.trim();
                methodStart = i;
                continue;
            }
            if (line.startsWith(".end method")) {
                currentMethod = null;
                continue;
            }

            if (currentMethod != null && line.contains(GenericConstants.ADDJSInterface)) {
                String[] registers = parseInvokeRegisters(line);
                if (registers.length < 3) {
                    System.out.println("[DEBUG] Skipping addJavascriptInterface - insufficient registers: " + line);
                    continue;
                }

                String objectRegister = registers[1];
                String nameRegister = registers[2];

                String interfaceName = findConstString(classLines, i, methodStart, nameRegister, currentMethod);
                String bridgeClass = findObjectType(classLines, i, methodStart, objectRegister, currentMethod);

                System.out.println("[DEBUG] Found addJavascriptInterface:");
                System.out.println("  Class: " + className);
                System.out.println("  Method: " + currentMethod);
                System.out.println("  Interface Name (initial): " + (interfaceName != null && !interfaceName.isEmpty() ? interfaceName : "(empty)"));
                System.out.println("  Bridge Class: " + (bridgeClass != null && !bridgeClass.isEmpty() ? bridgeClass : "(empty)"));
                System.out.println("  nameRegister: " + nameRegister);

                // Check if interface name is actually resolved (not empty, not a parameter placeholder)
                boolean isInterfaceNameResolved = interfaceName != null
                        && !interfaceName.isEmpty()
                        && !interfaceName.startsWith("param:")
                        && !interfaceName.equals(nameRegister);

                System.out.println("  Initial Resolution Success: " + isInterfaceNameResolved);

                // If interface name is unresolved, try advanced tracing methods
                if (!isInterfaceNameResolved) {
                    System.out.println("[DEBUG] Initial resolution failed, trying advanced methods...");

                    // Level 2: Try advanced parameter tracing
                    if (nameRegister != null) {
                        System.out.println("[DEBUG] Level 2: Attempting advanced tracing for register: " + nameRegister);
                        String advancedResolution = findInterfaceNameFromParameter(classLines, i, methodStart, nameRegister, currentMethod);
                        if (advancedResolution != null && !advancedResolution.isEmpty() && !advancedResolution.startsWith("param:")) {
                            System.out.println("[DEBUG] Level 2 SUCCESS: Resolved to: " + advancedResolution);
                            interfaceName = advancedResolution;
                            isInterfaceNameResolved = true;
                        } else {
                            System.out.println("[DEBUG] Level 2 FAILED: " + (advancedResolution == null ? "null" : (advancedResolution.isEmpty() ? "empty" : advancedResolution)));
                        }
                    }

                    // Level 3: Try fallback heuristic search if Level 2 failed
                    if (!isInterfaceNameResolved && nameRegister != null) {
                        System.out.println("[DEBUG] Level 3: Trying fallback - searching for const-string near addJavascriptInterface call");
                        String fallbackResolution = findConstStringNearInvoke(classLines, i, nameRegister);
                        if (fallbackResolution != null && !fallbackResolution.isEmpty() && !fallbackResolution.startsWith("param:")) {
                            System.out.println("[DEBUG] Level 3 SUCCESS: Found via fallback heuristic: " + fallbackResolution);
                            interfaceName = fallbackResolution;
                            isInterfaceNameResolved = true;
                        } else {
                            System.out.println("[DEBUG] Level 3 FAILED: " + (fallbackResolution == null ? "null" : (fallbackResolution.isEmpty() ? "empty" : fallbackResolution)));
                        }
                    }
                }

                // Final result
                if (!isInterfaceNameResolved) {
                    System.out.println("[DEBUG] All resolution attempts failed - storing with EMPTY interfaceObject");
                    interfaceName = "";
                } else {
                    System.out.println("[DEBUG] RESOLUTION SUCCESSFUL: " + interfaceName);
                }

                String methodList = buildMethodList(annotatedMethods.get(bridgeClass));
                System.out.println("  Bridge Methods Count: " + (methodList != null && !methodList.isEmpty() ? "found" : "empty"));
                System.out.println("  Final Interface Object: " + (interfaceName != null && !interfaceName.isEmpty() ? interfaceName : "(empty)"));

                try {
                    LoadUrlDB.storeBridgeDetails(
                            appAnalyzer.getAppDetails().getAppName(),
                            className,
                            bridgeClass != null ? bridgeClass : "",
                            interfaceName,
                            methodList,
                            currentMethod
                    );
                    System.out.println("  ✅ Stored successfully");
                } catch (SQLException e) {
                    System.err.println("  ❌ Failed to store: " + e.getMessage());
                    appAnalyzer.getLogger().getLogger().warning("Failed to store bridge details: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    private static String buildMethodList(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String method : methods) {
            builder.append(method).append("\n");
        }
        return builder.toString().trim();
    }

    private static String extractClassName(List<String> classLines) {
        for (String line : classLines) {
            if (line.startsWith(".class ")) {
                String[] parts = line.split(" ");
                return parts.length > 0 ? parts[parts.length - 1].trim() : null;
            }
        }
        return null;
    }

    private static String[] parseInvokeRegisters(String line) {
        int start = line.indexOf('{');
        int end = line.indexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return new String[0];
        }
        String inside = line.substring(start + 1, end);
        String[] parts = inside.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String findConstString(List<String> lines, int index, int methodStart, String register, String methodLine) {
        String currentRegister = register;
        for (int i = index; i >= methodStart; i--) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            // Follow register moves (move-object, move-object/from16, move-object/16)
            String movedFrom = resolveMoveSource(line, currentRegister);
            if (!movedFrom.isEmpty()) {
                currentRegister = movedFrom;
                continue;
            }
            if ((line.contains("const-string " + currentRegister + ",")
                    || line.contains("const-string/jumbo " + currentRegister + ","))
                    && line.contains("\"")) {
                int firstQuote = line.indexOf('"');
                int lastQuote = line.lastIndexOf('"');
                if (firstQuote >= 0 && lastQuote > firstQuote) {
                    return line.substring(firstQuote + 1, lastQuote).trim();
                }
            }
        }
        // Try inter-procedural propagation for parameter registers
        if (currentRegister != null && currentRegister.startsWith("p")) {
            String fromCaller = resolveFromCallersForString(lines, methodLine, currentRegister);
            if (!fromCaller.isEmpty()) {
                return fromCaller;
            }
        }

        // If still not found and it's a parameter, search for ANY const-string near the addJavascriptInterface call
        // This handles cases where the interface name is loaded into a temp register before being passed as parameter
        if (register != null && register.startsWith("p")) {
            int searchStart = Math.max(methodStart, index - 20);
            int searchEnd = Math.min(lines.size(), index + 2);

            for (int i = searchStart; i <= searchEnd; i++) {
                if (i < 0 || i >= lines.size()) continue;
                String line = lines.get(i);
                if (line == null) continue;

                // Look for any const-string that might be the interface name
                if ((line.contains("const-string ") || line.contains("const-string/jumbo ")) && line.contains("\"")) {
                    int firstQuote = line.indexOf('"');
                    int lastQuote = line.lastIndexOf('"');
                    if (firstQuote >= 0 && lastQuote > firstQuote) {
                        String value = line.substring(firstQuote + 1, lastQuote).trim();
                        // Simple filter: looks like interface name if it's short, starts with letter, mostly alphanumeric
                        if (value.length() > 0 && value.length() < 100
                            && Character.isLetterOrDigit(value.charAt(0))
                            && !value.contains("://") && !value.startsWith("http") && !value.startsWith("android")) {
                            return value;
                        }
                    }
                }
            }
        }

        return "";
    }

    private static String findObjectType(List<String> lines, int index, int methodStart, String register, String methodLine) {
        String currentRegister = register;
        for (int i = index; i >= methodStart; i--) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            // Follow register moves (move-object, move-object/from16, move-object/16)
            String movedFrom = resolveMoveSource(line, currentRegister);
            if (!movedFrom.isEmpty()) {
                currentRegister = movedFrom;
                continue;
            }
            // Track check-cast to refine object type
            if (line.contains("check-cast " + currentRegister + ",")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String castType = parts[1].trim();
                    if (!castType.isEmpty()) {
                        return castType;
                    }
                }
            }
            if (line.contains("new-instance " + currentRegister + ",")) {
                return extractTypeAfterComma(line);
            }
            // Handle invoke-direct <init> on the object register
            String initType = extractInvokeInitType(line, currentRegister);
            if (!initType.isEmpty()) {
                return initType;
            }
            if (line.contains("iget-object " + currentRegister + ",") || line.contains("sget-object " + currentRegister + ",")) {
                return extractTypeFromField(line);
            }
            if (line.contains("move-result-object " + currentRegister) && i > methodStart) {
                String invokeType = findInvokeReturnType(lines, i - 1);
                if (!invokeType.isEmpty()) {
                    return invokeType;
                }
            }
        }
        // Try inter-procedural propagation for parameter registers
        if (currentRegister != null && currentRegister.startsWith("p")) {
            String fromCaller = resolveFromCallersForType(lines, methodLine, currentRegister);
            if (!fromCaller.isEmpty()) {
                return fromCaller;
            }
        }
        // If register is a parameter and no concrete type was found, infer from method signature
        String inferredParamType = inferParamType(methodLine, currentRegister);
        if (!inferredParamType.isEmpty()) {
            return inferredParamType;
        }
        if (currentRegister != null && currentRegister.startsWith("p")) {
            return "param:" + currentRegister;
        }
        return currentRegister != null ? currentRegister : "";
    }

    private static String extractInvokeInitType(String line, String targetRegister) {
        if (line == null || targetRegister == null) {
            return "";
        }
        if (!line.startsWith("invoke-direct") || !line.contains("-><init>")) {
            return "";
        }
        int start = line.indexOf('{');
        int end = line.indexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return "";
        }
        String regs = line.substring(start + 1, end);
        if (!regs.contains(targetRegister)) {
            return "";
        }
        int typeStart = line.indexOf('L', end);
        int typeEnd = line.indexOf(';', typeStart);
        if (typeStart >= 0 && typeEnd > typeStart) {
            return line.substring(typeStart, typeEnd + 1).trim();
        }
        return "";
    }

    private static String resolveFromCallersForString(List<String> lines, String methodLine, String paramRegister) {
        String descriptor = extractMethodDescriptor(methodLine);
        if (descriptor.isEmpty()) {
            return "";
        }
        int paramIndex = getParamIndexFromRegister(methodLine, paramRegister);
        if (paramIndex < 0) {
            return "";
        }
        int methodStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            if (line.startsWith(".method ")) {
                methodStart = i;
                continue;
            }
            if (line.startsWith(".end method")) {
                methodStart = -1;
                continue;
            }
            if (methodStart >= 0 && line.startsWith("invoke") && line.contains("->" + descriptor)) {
                String[] regs = parseInvokeRegistersFlexible(line);
                int argIndex = paramIndex + 1; // instance method receiver at index 0
                if (argIndex < regs.length) {
                    String argReg = regs[argIndex];
                    String resolved = traceStringInMethod(lines, i, methodStart, argReg);
                    if (!resolved.isEmpty()) {
                        return resolved;
                    }
                }
            }
        }
        return "";
    }

    private static String resolveFromCallersForType(List<String> lines, String methodLine, String paramRegister) {
        String descriptor = extractMethodDescriptor(methodLine);
        if (descriptor.isEmpty()) {
            return "";
        }
        int paramIndex = getParamIndexFromRegister(methodLine, paramRegister);
        if (paramIndex < 0) {
            return "";
        }
        int methodStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            if (line.startsWith(".method ")) {
                methodStart = i;
                continue;
            }
            if (line.startsWith(".end method")) {
                methodStart = -1;
                continue;
            }
            if (methodStart >= 0 && line.startsWith("invoke") && line.contains("->" + descriptor)) {
                String[] regs = parseInvokeRegistersFlexible(line);
                int argIndex = paramIndex + 1; // instance method receiver at index 0
                if (argIndex < regs.length) {
                    String argReg = regs[argIndex];
                    String resolved = traceTypeInMethod(lines, i, methodStart, argReg);
                    if (!resolved.isEmpty()) {
                        return resolved;
                    }
                }
            }
        }
        return "";
    }

    private static String traceStringInMethod(List<String> lines, int index, int methodStart, String register) {
        String currentRegister = register;
        for (int i = index; i >= methodStart; i--) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            String movedFrom = resolveMoveSource(line, currentRegister);
            if (!movedFrom.isEmpty()) {
                currentRegister = movedFrom;
                continue;
            }
            if ((line.contains("const-string " + currentRegister + ",")
                    || line.contains("const-string/jumbo " + currentRegister + ","))
                    && line.contains("\"")) {
                int firstQuote = line.indexOf('"');
                int lastQuote = line.lastIndexOf('"');
                if (firstQuote >= 0 && lastQuote > firstQuote) {
                    return line.substring(firstQuote + 1, lastQuote).trim();
                }
            }
        }
        return "";
    }

    private static String traceTypeInMethod(List<String> lines, int index, int methodStart, String register) {
        String currentRegister = register;
        for (int i = index; i >= methodStart; i--) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            String movedFrom = resolveMoveSource(line, currentRegister);
            if (!movedFrom.isEmpty()) {
                currentRegister = movedFrom;
                continue;
            }
            if (line.contains("check-cast " + currentRegister + ",")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].trim();
                }
            }
            if (line.contains("new-instance " + currentRegister + ",")) {
                return extractTypeAfterComma(line);
            }
            String initType = extractInvokeInitType(line, currentRegister);
            if (!initType.isEmpty()) {
                return initType;
            }
            if (line.contains("iget-object " + currentRegister + ",") || line.contains("sget-object " + currentRegister + ",")) {
                return extractTypeFromField(line);
            }
        }
        return "";
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
                    int startNum = Integer.parseInt(startReg.substring(1));
                    int endNum = Integer.parseInt(endReg.substring(1));
                    int size = Math.max(0, endNum - startNum + 1);
                    String[] regs = new String[size];
                    for (int i = 0; i < size; i++) {
                        regs[i] = prefix + (startNum + i);
                    }
                    return regs;
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

    private static String toReadableType(String smaliType) {
        if (smaliType == null || smaliType.isEmpty()) {
            return "";
        }
        if (smaliType.startsWith("[")) {
            return toReadableType(smaliType.substring(1)) + "[]";
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
                    String type = smaliType.substring(1, smaliType.length() - 1).replace('/', '.');
                    int lastDot = type.lastIndexOf('.');
                    return lastDot >= 0 ? type.substring(lastDot + 1) : type;
                }
                return smaliType;
        }
    }

    private static String extractTypeAfterComma(String line) {
        int comma = line.indexOf(',');
        if (comma < 0 || comma + 1 >= line.length()) {
            return "";
        }
        return line.substring(comma + 1).trim();
    }

    private static String extractTypeFromField(String line) {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 >= line.length()) {
            return "";
        }
        String type = line.substring(colon + 1).trim();
        int space = type.indexOf(' ');
        if (space > 0) {
            type = type.substring(0, space);
        }
        return type;
    }

    private static String normalizeRegister(String register) {
        String trimmed = register.trim();
        int end = trimmed.length();
        while (end > 0 && !Character.isLetterOrDigit(trimmed.charAt(end - 1))) {
            end--;
        }
        return end > 0 ? trimmed.substring(0, end) : "";
    }

    private static String resolveMoveSource(String line, String targetRegister) {
        if (targetRegister == null || targetRegister.isEmpty()) {
            return "";
        }
        if (line.contains("move-object") || line.contains("move-object/from16") || line.contains("move-object/16")) {
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                String dest = parts[0].trim().split("\\s+")[parts[0].trim().split("\\s+").length - 1];
                if (dest.equals(targetRegister)) {
                    return parts[1].trim();
                }
            }
        }
        return "";
    }

    private static String findInvokeReturnType(List<String> lines, int index) {
        for (int i = index; i >= 0; i--) {
            String line = lines.get(i);
            if (line.startsWith("invoke")) {
                int closeParen = line.indexOf(')');
                if (closeParen > 0 && closeParen + 1 < line.length()) {
                    return line.substring(closeParen + 1).trim();
                }
            }
            if (line.startsWith(".method ")) {
                break;
            }
        }
        return "";
    }

    private static String inferParamType(String methodLine, String register) {
        if (methodLine == null || register == null || !register.startsWith("p")) {
            return "";
        }
        String normalized = normalizeRegister(register);
        if (normalized.isEmpty()) {
            return "";
        }
        int parenStart = methodLine.indexOf('(');
        int parenEnd = methodLine.indexOf(')');
        if (parenStart < 0 || parenEnd <= parenStart) {
            return "";
        }
        String params = methodLine.substring(parenStart + 1, parenEnd);
        List<String> types = parseParamTypes(params);
        boolean isStatic = methodLine.contains(" static ");
        int regIndex;
        try {
            regIndex = Integer.parseInt(normalized.substring(1));
        } catch (NumberFormatException e) {
            return "";
        }
        if (!isStatic) {
            regIndex -= 1; // p0 is 'this' for instance methods
        }
        if (regIndex < 0 || regIndex >= types.size()) {
            // Special-case known signature for addJavascriptInterface
            if (methodLine.contains("addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V")) {
                if (register.startsWith("p1")) {
                    return "Ljava/lang/Object;";
                }
                if (register.startsWith("p2")) {
                    return "Ljava/lang/String;";
                }
            }
            return "";
        }
        return types.get(regIndex);
    }

    private static List<String> parseParamTypes(String params) {
        List<String> types = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end > i) {
                    types.add(params.substring(i, end + 1));
                    i = end + 1;
                } else {
                    break;
                }
            } else if (c == '[') {
                int start = i;
                i++;
                if (i < params.length() && params.charAt(i) == 'L') {
                    int end = params.indexOf(';', i);
                    if (end > i) {
                        types.add(params.substring(start, end + 1));
                        i = end + 1;
                    } else {
                        break;
                    }
                } else if (i < params.length()) {
                    types.add(params.substring(start, i + 1));
                    i++;
                }
            } else {
                types.add(String.valueOf(c));
                i++;
            }
        }
        return types;
    }

	/**
	 * Finds interface name from a parameter register by tracing through inter-procedural calls.
	 * When the interface name is passed as a parameter, this tries to trace it back to the caller.
	 *
	 * @param classLines List of all Smali lines in the class
	 * @param index Current index in the addJavascriptInterface call
	 * @param methodStart Index where the method starts
	 * @param paramRegister The parameter register (e.g., p1, p2)
	 * @param methodLine The method signature
	 * @return Resolved interface name or empty string if unresolved
	 */
	private static String findInterfaceNameFromParameter(List<String> classLines, int index, int methodStart, String paramRegister, String methodLine) {
		// Try to find where this parameter is assigned within the method
		for (int i = index; i >= methodStart; i--) {
			String line = classLines.get(i);
			if (line == null) continue;

			// Look for move-object instructions that assign to this parameter
			if (line.contains("move-object") && line.contains(paramRegister)) {
				String[] parts = line.split(",");
				if (parts.length >= 2) {
					String sourceReg = parts[parts.length - 1].trim();
					// Recursively trace the source register
					return findConstString(classLines, i, methodStart, sourceReg, methodLine);
				}
			}
		}

		// Try to find const-string assignments nearby
		for (int i = index; i >= Math.max(methodStart, index - 20); i--) {
			String line = classLines.get(i);
			if (line == null) continue;

			if ((line.contains("const-string " + paramRegister + ",")
					|| line.contains("const-string/jumbo " + paramRegister + ","))
					&& line.contains("\"")) {
				int firstQuote = line.indexOf('"');
				int lastQuote = line.lastIndexOf('"');
				if (firstQuote >= 0 && lastQuote > firstQuote) {
					return line.substring(firstQuote + 1, lastQuote).trim();
				}
			}
		}

		return "";
	}

	/**
	 * Finds const-string values near the addJavascriptInterface invocation.
	 * This is a fallback method that searches for string constants in the vicinity of the call.
	 *
	 * @param classLines List of all Smali lines in the class
	 * @param invokeIndex Index of the addJavascriptInterface invocation
	 * @param nameRegister The name parameter register
	 * @return Found const-string value or empty string if none found
	 */
	private static String findConstStringNearInvoke(List<String> classLines, int invokeIndex, String nameRegister) {
		// Search nearby lines (before and after) for const-string that might be the interface name
		int searchStart = Math.max(0, invokeIndex - 10);
		int searchEnd = Math.min(classLines.size(), invokeIndex + 5);

		// First, search backwards (more likely to find the setup before the call)
		for (int i = invokeIndex - 1; i >= searchStart; i--) {
			String line = classLines.get(i);
			if (line == null) continue;

			// Look for any const-string, not just for our register
			if ((line.contains("const-string ") || line.contains("const-string/jumbo "))
					&& line.contains("\"")) {
				// Extract the string value
				int firstQuote = line.indexOf('"');
				int lastQuote = line.lastIndexOf('"');
				if (firstQuote >= 0 && lastQuote > firstQuote) {
					String value = line.substring(firstQuote + 1, lastQuote).trim();
					// Simple heuristic: interface names are usually short, alphanumeric, camelCase
					// and don't start with common prefixes like "http", "android", etc.
					if (isLikelyInterfaceName(value)) {
						return value;
					}
				}
			}
		}

		return "";
	}

	/**
	 * Simple heuristic to determine if a string looks like a JavaScript interface object name.
	 * Interface names are typically:
	 * - Short (less than 50 chars)
	 * - CamelCase or lowercase identifier
	 * - Alphanumeric with underscores
	 * - Don't contain slashes, dots (unless package-qualified)
	 *
	 * @param value The string to check
	 * @return true if the string looks like an interface name
	 */
	private static boolean isLikelyInterfaceName(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}

		// Too long - probably not an interface name
		if (value.length() > 100) {
			return false;
		}

		// Check for common patterns that are NOT interface names
		if (value.contains("://") || value.startsWith("http") || value.startsWith("file")) {
			return false; // Looks like a URL
		}

		if (value.contains("/") && value.length() > 50) {
			return false; // Looks like a path
		}

		// Interface names typically start with a letter
		if (!Character.isLetter(value.charAt(0))) {
			return false;
		}

		// Check if it contains mostly word characters
		long wordCharCount = value.chars().filter(c -> Character.isLetterOrDigit(c) || c == '_').count();
		if (wordCharCount < (value.length() * 0.8)) {
			return false; // Too many special characters
		}

		return true;
	}
}
