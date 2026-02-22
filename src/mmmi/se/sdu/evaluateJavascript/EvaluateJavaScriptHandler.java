/**
 * 
 */
package mmmi.se.sdu.evaluateJavascript;

import java.util.List;
import java.util.StringTokenizer;

import mmmi.se.sdu.SmaliContent.SmaliContent;
import mmmi.se.sdu.constants.GenericConstants;
import mmmi.se.sdu.db.LoadUrlDB;
import mmmi.se.sdu.loadurl.StringOptimizer;
import mmmi.se.sdu.main.ApplicationAnalysis;

/**
 * @author abhishektiwari
 *
 */
public class EvaluateJavaScriptHandler {
	
	/**
	 * Analyzes Smali files to extract evaluateJavascript calls from WebView usage.
	 * Similar to LoadURLAnalyzer but handles WebView.evaluateJavascript() instead of loadUrl().
	 *
	 * @param appAnalyzer ApplicationAnalysis instance containing parsed Smali content
	 */
	public static void checkEvaluateJavaScript(ApplicationAnalysis appAnalyzer){
		
		if (appAnalyzer == null || appAnalyzer.getSmaliData() == null) {
			logError("Invalid application analyzer or Smali data");
			return;
		}

		SmaliContent smaliData = appAnalyzer.getSmaliData();

		for (List<String> fileContentInSmaliFormat : smaliData.classContent){
			String[] fileContentInArray = fileContentInSmaliFormat.toArray(new String[0]);
			String className = extractClassName(fileContentInArray[0]);

			for(int index = 0; index < fileContentInArray.length; index++){
				String line = fileContentInArray[index];

				if(line.contains(GenericConstants.EVALUATE_JS)){
					String rawJsString = extractJavaScriptFromEvaluate(fileContentInArray, index);
					LoadUrlDB.storeIntentDetails(appAnalyzer, className, rawJsString);
				}
			}
		}
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
			String[] tokens = classHeader.split("\\s+");
			for (String token : tokens) {
				if (token.startsWith("L") && token.endsWith(";")) {
					return token.substring(token.lastIndexOf('/') + 1, token.length() - 1);
				}
			}
		} catch (Exception e) {
			logError("Failed to parse class header: " + classHeader);
		}

		return "dummyClass";
	}

	/**
	 * Extracts the JavaScript content passed to evaluateJavascript by performing backward slicing.
	 *
	 * @param fileContentInArray Array of Smali code lines
	 * @param index The index of the evaluateJavascript call
	 * @return The extracted JavaScript string or descriptive message
	 */
	public static String extractJavaScriptFromEvaluate(String[] fileContentInArray, int index){

		if (fileContentInArray == null || index < 0 || index >= fileContentInArray.length) {
			return "Invalid evaluateJavascript index";
		}

		String[] inputTempReg = new String[5];
		int i = 0;

		StringTokenizer st = new StringTokenizer(fileContentInArray[index], "{,}");
		
		while(st.hasMoreTokens() && i < 5){
			inputTempReg[i] = st.nextToken();
			i++;
		}
		
		if (i < 3 || inputTempReg[2] == null) {
			return "Could not extract register from evaluateJavascript call";
		}

		int tempIndex = index;
		String targetRegister = inputTempReg[2];

		//Extract JavaScript by walking backward
		while(tempIndex > 0 && !fileContentInArray[tempIndex].contains(".method")){
			tempIndex--;

			//Handle const-string
			if(fileContentInArray[tempIndex].contains(targetRegister) && fileContentInArray[tempIndex].contains("const-string")){
				String rawJavaScript = extractStringFromConstString(fileContentInArray[tempIndex]);
				if (!rawJavaScript.isEmpty()) {
					return rawJavaScript;
				}
			}
			//Handle iget-object
			else if(fileContentInArray[tempIndex].contains(targetRegister) && fileContentInArray[tempIndex].contains("iget-object")){
				return "iget-object: Field access - requires field analysis";
			}
			//Handle move-result-object
			else if(fileContentInArray[tempIndex].contains(targetRegister) && fileContentInArray[tempIndex].contains("move-result-object")){
				if(tempIndex >= 2 && fileContentInArray[tempIndex-2].contains(GenericConstants.STRINGBUILDER_TOSTRING)){
					return StringOptimizer.string_builder(fileContentInArray, tempIndex-2);
				}
				else{
					return "move-result-object: Method return value";
				}
			}
			//Handle sget-object
			else if(fileContentInArray[tempIndex].contains(targetRegister) && fileContentInArray[tempIndex].contains("sget-object")){
				return "sget-object: Static field access - requires field analysis";
			}
			//Handle inter-procedural
			else if(targetRegister.contains("p")){
				return "Parameter: Cannot determine value statically";
			}
		}

		return "Could not determine evaluateJavascript argument";
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
			logError("Failed to extract string from: " + line);
		}
		
		return "";
	}

	/**
	 * Logs an error message.
	 *
	 * @param message The error message to log
	 */
	private static void logError(String message) {
		System.err.println("[ERROR] " + message);
	}

}
