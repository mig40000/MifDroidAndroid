package de.potsdam.slicer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;  

import de.potsdam.ApplicationDetails.ApplicationDetails;

import java.util.regex.Matcher;

public class Slicer {

	public List<List<String>> classContent;
	public Logger logger;
	//public MainHandler app;
	public ApplicationDetails app;
	public Map<String, SliceClass> classMap = new HashMap<String, SliceClass>();
	public TreeSet<SliceBase> slice = new TreeSet<SliceBase>();
	public List<String> sliceStrings = new LinkedList<String>();
	public Queue<SliceVarUse> registerQueue = new LinkedList<SliceVarUse>();
	public Set<SliceVar> trackedSet = new HashSet<SliceVar>();
	public Set<SliceVar> toSliceSet = new HashSet<SliceVar>();
	public List<SliceMethod> annotatedMethods = new LinkedList<SliceMethod>();
	public Pattern pat = Pattern.compile("v\\d+|p\\d+");
	public Pattern stringPattern = Pattern.compile("const-string .*, \"(.*)\"");
	public Pattern invokePattern = Pattern.compile("invoke.*L(.*)->(.*)");
	public SliceClass currentWebView = null;
	public int sliceCount = 1;
	public boolean jsEnabled = false;
	public boolean usesWebView = false;
	public boolean injects = false;
	public int annotated = 0;
	public int invoked = 0;
	public String interfaceObject = "";
	public String methodNames = new String();
	private int saveAltDbSkipCount = 0;

	public List<String> extraMethods = new LinkedList<String>();
	public Queue<SliceMethod> extraMethodsQueue = new LinkedList<SliceMethod>();
	public List<String> sources = new LinkedList<String>();
	public List<String> foundSources = new LinkedList<String>();
	
	

	public Slicer(List<List<String>> class_list, Logger logger, ApplicationDetails app) throws IOException {
		this.classContent = class_list;
		this.logger = logger;
		this.app = app;

		try {
			// Prepare slice objects from decompiled code
			prepareSlice();

			// If no variables to slice found, save empty result
			if (this.toSliceSet.isEmpty()) {
				saveDB("");
				return;
			}

			// Process each variable found in toSliceSet
			Integer previousLineNumber = 0;
			for (SliceVar sVar : this.toSliceSet) {
				try {
					// Get last usage of this variable
					SliceVarUse lastUsage = sVar.varUseMap.lastEntry().getValue();

					// Skip duplicate line numbers
					if (previousLineNumber.equals(lastUsage.lineNumber)) {
						continue;
					}

					// Backward slice from this variable usage
					sliceAt(lastUsage.className, lastUsage.methodName, lastUsage.lineNumber, lastUsage.sliceVar.name);

					// Extract WebView class from the slice
					currentWebView = extractWebViewClass();
					checkJsEnabled();

					// If WebView found, perform analysis
					if (currentWebView != null) {
						// Read sources once per WebView class
						readSources();

						// Log all annotated methods for this WebView
						for (SliceMethod m : this.annotatedMethods) {
							if (m.className != null && m.className.equals(currentWebView.className)) {
								this.logger.info("Annotated List => Class: " + m.className + " Method: " + m.name);
							}
						}

						// Save the slice and find leaks
						saveSlice(lastUsage.className, currentWebView);
						findLeaks();
					}

				// Save results to database
				saveDB(lastUsage.className);
				saveAltDB(lastUsage.className, lastUsage.methodName);

					// Reset for next iteration
					clearSlice();
					previousLineNumber = lastUsage.lineNumber;

				} catch (Exception e) {
					this.logger.warning("Error processing variable: " + e.getMessage());
					e.printStackTrace();
					clearSlice();
				}
			}
		} catch (Exception e) {
			this.logger.severe("Error in Slicer constructor: " + e.getMessage());
			e.printStackTrace();
			throw new IOException("Slicer initialization failed", e);
		}
	}

	/**
	 * read the sources file which contains possible information leak sources
	 */
	public void readSources() {
		File file = new File("Database/sources.txt");
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = br.readLine()) != null) {

				// ignore WebView getSettings because it is in every slice
				if (!line.contains("Landroid/webkit/WebView;->getSettings()")) {
					this.sources.add(line);
				}
			}
		} catch (IOException e) {
			System.out.println("error loading sources.txt, check filepath");
		}
	}

	/**
	 * uses the list of known sources to find possible leaks in the complete slice
	 * (slice + injected methods)
	 */
	public void findLeaks() {

		// loop through slice

		for (SliceBase sliceLine : this.slice) {
			for (String s : this.sources) {
				if (sliceLine.line.trim().contains(s.trim())) {
					this.foundSources.add(s);
				}
			}
		}
	//	System.out.println("I am here");
		// loop through annotated and invoked methods

	//	System.out.println("size this.extraMethods " + this.extraMethods.size() + "this.sources " + this.sources.size());
		for (String sliceLine : this.extraMethods) {
			for (String s : this.sources) {
				if (sliceLine.trim().contains(s.trim())) {
					this.foundSources.add(s);
				}
			}
		}
		this.sources.clear();
	}

	/**
	 * Save statistics in an sqlite3 database
	 */
	public void saveDB(String className) {

		String sql = "INSERT INTO webviews (apk, class_name, slice, uses_webview, permission_set, js_enabled, injects, injected_class, annotated, invoked, leaks) VALUES (?,?,?,?,?,?,?,?,?,?,?)";

		Connection conn = null;
		if(this.currentWebView != null) {
		try{
			conn = DriverManager.getConnection("jdbc:sqlite:Database/Intent.sqlite");
		} catch (SQLException e1) {
			System.out.println(e1.getMessage());
		}
		
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, app.getAppName());
			stmt.setString(2, className);
			if (this.currentWebView == null) {
				stmt.setString(3, "");
			} else {
				stmt.setString(3, "slice" + (this.sliceCount-1) + ".smali");
			}
			stmt.setBoolean(4, this.usesWebView);
			//Changes Abhishek
		//	if (app.permission.contains("android.permission.INTERNET")) {
				stmt.setBoolean(5, true);
		//	} else {
			//	stmt.setBoolean(5, false);
			//}
			stmt.setBoolean(6, this.jsEnabled);
			stmt.setBoolean(7, this.injects);
			if (this.currentWebView == null) {
				stmt.setString(8, "");
			} else {
				stmt.setString(8, this.currentWebView.className);	
			}
			stmt.setInt(9, this.annotated);
			stmt.setInt(10, this.invoked);
			stmt.setString(11, this.foundSources.toString());
			stmt.executeUpdate();
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
		}

	}
	
	/**
	 * Save bridge details discovered during slicing to the database.
	 * Now uses LoadUrlDB.storeBridgeDetails() for consistent schema handling.
	 */
	public void saveAltDB(String className, String methodName) {

		if(this.currentWebView == null) {
			saveAltDbSkipCount++;
			if (saveAltDbSkipCount % 25 == 1) {
				System.out.println("[DEBUG] Slicer: Skipping saveAltDB - currentWebView is null (count=" + saveAltDbSkipCount + ")");
			}
			return;
		}

		try {
			String bridgeClass = this.currentWebView.className != null ? this.currentWebView.className : "";
			String interfaceObject = this.interfaceObject != null ? this.interfaceObject : "";
			String bridgeMethods = this.methodNames != null ? this.methodNames : "";

			System.out.println("[DEBUG] Slicer found bridge interface:");
			System.out.println("  App: " + app.getAppName());
			System.out.println("  Initiating Class: " + className);
			System.out.println("  Bridge Class: " + bridgeClass);
			System.out.println("  Interface Object: " + interfaceObject);
			System.out.println("  Method: " + methodName);

			// Use LoadUrlDB.storeBridgeDetails() for consistent schema handling
			de.potsdam.db.LoadUrlDB.storeBridgeDetails(
				app.getAppName(),
				className,
				bridgeClass,
				interfaceObject,
				bridgeMethods,
				methodName
			);

			System.out.println("  ✅ Stored successfully via Slicer");

		} catch (SQLException e) {
			System.err.println("[ERROR] Slicer failed to store bridge details: " + e.getMessage());
			e.printStackTrace();
		}

	}
	
	public String removeRedundantBridgeMethod(String bridgeMethods) {
		String[] methods = bridgeMethods.split("\n");
		String newMethods = "";
		
		ArrayList<String> tempList = new ArrayList<String>();
		
		for(String method: methods) {
			System.out.println("methods now are " + method);
			tempList.add(method);
		}
		
		Set<String> s = new LinkedHashSet<String>(tempList);
	//	System.out.println("set now is " + s);
		
		 Iterator<String> i=s.iterator();  
		 
		 while(i.hasNext())  
         {  
			 newMethods = newMethods.concat(i.next());
         }  
		
		 return newMethods;
	}
	

	/**
	 * extract all strings in the slice into a list
	 */
	public void extractStringsFromSlice() {

		// gets every string that is added with const-string

		Matcher match;
		for (SliceBase s : this.slice) {

			match = this.stringPattern.matcher(s.line);

			if (match.find()) {
				this.sliceStrings.add(match.group(1));

			}
		}
	}

	/**
	 * optionally downloads the Webpage with wget for further analysis url found
	 * with extractStringsFromSlice
	 */
	public void downloadUrls() {

		extractStringsFromSlice();

		File dir = new File("output/" + app.getAppName() + "/downloads");
		dir.mkdirs();

		for (String s : this.sliceStrings) {
			if (s.startsWith("http")) {

				// download and save the url
				// first process gets all javascript files that are used on the page
				// second process gets the html of the page for inline javascript
				try {
					ProcessBuilder pb = new ProcessBuilder("wget", "-nd", "-r", "-l", "1", "-e", "robots=off", "-P",
							dir.getAbsolutePath(), "-A", "js", s);
					Process p = pb.start();
					ProcessBuilder pb2 = new ProcessBuilder("wget", "-nd", "-e", "robots=off", "-P",
							dir.getAbsolutePath(), "-A", "html", s);
					Process p2 = pb2.start();

					BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
					BufferedReader errReader = new BufferedReader(new InputStreamReader(p2.getErrorStream()));

					String line = "";
					while ((line = reader.readLine()) != null) {
						this.logger.info(line + "\n");
					}
					while ((line = errReader.readLine()) != null) {
						this.logger.info(line + "\n");
					}
					p.waitFor();
					p2.waitFor();

				} catch (IOException | InterruptedException e) {
					this.logger.warning("Could not download a website: " + e.getMessage());
				}

			}

			if (s.startsWith("file://")) {

				// copy local file

			}

			if (s.startsWith("javascript:")) {

				// create new file for this
				// same for webview.evaluatejavascript("jscode", ...)

			}

		}

	}

	public SliceClass extractWebViewClass() {

		// first we look for a addJavascriptInterface call and take the register it is
		// adding (= the injected class)
		// then we look for a new-instance {register} where the register is the injected
		// class from before

		String searchRegister;
		String objRegister = "";
		SliceBase currentSlice;
		Iterator<SliceBase> it = this.slice.descendingIterator();
		boolean nextInvoke = false;
		int count = 0;
		boolean flag = false;

		// searchRegister is something that will not be found for now
		searchRegister = "x0";
		
		

		while (it.hasNext()) {
			currentSlice = it.next();
			if (currentSlice.line == null) {
				continue;
			}

			if (currentSlice.line.contains(";->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V")) {

				// get register by splitting at comma, choosing everything right from the comma
				// and stripping a space at the beginning
				searchRegister = currentSlice.line.split(",")[1].substring(1);
				objRegister = currentSlice.line.split(",")[2].substring(1);
				objRegister = objRegister.replace("}", "");
			//	System.out.println("Register is Abhi " + objRegister);
				flag = true;
				this.injects = true;
			}
			
			if (currentSlice.line.contains("const-string " + objRegister) && flag) {
				// this should be the instance register -- may not work in all cases
				System.out.println("Current Slice is " + currentSlice.line);
				System.out.println("Interface Object is " + currentSlice.line.split(",")[1].substring(1));
				this.interfaceObject = currentSlice.line.split(",")[1].substring(1);
				this.interfaceObject = this.interfaceObject.replace("\"", "");
				this.interfaceObject = this.interfaceObject.trim();
				flag = false;
				//count++;
			}
			if (currentSlice.line.contains("const-string/jumbo " + objRegister) && flag) {
				// this should be the instance register -- may not work in all cases
				System.out.println("Current Slice is " + currentSlice.line);
				System.out.println("Interface Object is " + currentSlice.line.split(",")[1].substring(1));
				this.interfaceObject = currentSlice.line.split(",")[1].substring(1);
				this.interfaceObject = this.interfaceObject.replace("\"", "");
				this.interfaceObject = this.interfaceObject.trim();
				flag = false;
			}

			// Track register moves to follow the actual object register
			String movedFrom = resolveMoveSource(currentSlice.line, searchRegister);
			if (!movedFrom.isEmpty()) {
				searchRegister = movedFrom;
			}

			// If there is a check-cast, use its type as a hint
			if (currentSlice.line.contains("check-cast " + searchRegister + ",")) {
				String[] parts = currentSlice.line.split(",");
				if (parts.length >= 2) {
					String className = parts[1].trim();
					SliceClass result = this.classMap.get(className);
					if (result != null) {
						return result;
					}
				}
			}

			if (currentSlice.line.contains("new-instance " + searchRegister)) {

				// this time we get the classname from "new-instance register, classname"
				String[] parts = currentSlice.line.split(",");
				if (parts.length >= 2) {
					String className = parts[1].substring(1).trim();
					return this.classMap.get(className);
				}
			}

			if (currentSlice.line.contains("iget-object " + searchRegister) || currentSlice.line.contains("sget-object " + searchRegister)) {
				String[] parts = currentSlice.line.split(":");
				if (parts.length >= 2) {
					return this.classMap.get(parts[1].trim());
				}
			}

			if (nextInvoke == true && currentSlice.line.contains("invoke")) {
				String[] parts = currentSlice.line.split("\\)");
				if (parts.length >= 2) {
					return this.classMap.get(parts[1].trim()); // return value of the last invoke
				}
			}

			if (currentSlice.line.contains("move-result-object " + searchRegister)) { // if we find a move result object we need the return value of the last invoke
				nextInvoke = true;
			}
		}

		return null;
	}

	private String resolveMoveSource(String line, String targetRegister) {
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

	/**
	 * check that there is a call to setJavaScriptEnabled in our slice
	 * 
	 * @return true if js is enabled, else false
	 */
	public boolean checkJsEnabled() {

		Iterator<SliceBase> it = this.slice.descendingIterator();

		while (it.hasNext()) {
			if (it.next().line.contains("Landroid/webkit/WebSettings;->setJavaScriptEnabled")) {
				this.jsEnabled = true;
				return true;
			}
		}

		return false;
	}

	/**
	 * save the slice in a new file in a new directory called output/apkname/
	 * 
	 * @param className
	 * @param currentWebView
	 */
	public void saveSlice(String className, SliceClass currentWebView) {

		SliceMethod invokedMethod;
		
		System.out.println("Here in saveSlice");
		this.methodNames = "";

		try {
			File dir = new File("output/" + app.getAppName());
			dir.mkdirs();
			File sliceFile = new File(dir, "slice" + this.sliceCount + ".smali");
			sliceFile.createNewFile();
			FileWriter fstream = new FileWriter(sliceFile);
			BufferedWriter bw = new BufferedWriter(fstream);

			for (SliceBase s : this.slice) {
				bw.write(s.line);
				bw.newLine();
			}

		// add all annotated methods that are injected
		bw.newLine();
		bw.write("Interface Methods:");
		bw.newLine();

		for (SliceMethod a : this.annotatedMethods) {
			if (a.className != null && a.className.equals(currentWebView.className)) {
				this.annotated++;
				for (String s : a.sourceCode) {
					this.extraMethods.add(s);

						if(s.contains(".method ")) {
						//	
							this.methodNames = this.methodNames.concat(s);
							this.methodNames = this.methodNames.concat("\n");
						//	System.out.println("Method is " + s);
									
							
						}
						
						
						bw.write(s);
						bw.newLine();
					}
					bw.newLine();

					// add invoked methods to the queue
					for (String s : a.invokedMethods) {
						invokedMethod = findMethod(s);

						if (invokedMethod == null) {
							continue;
						}

						this.extraMethodsQueue.add(invokedMethod);

					}
				}
			}

			// recursively add invoked methods to the queue and iterate over them to add the
			// code and more invoked methods
			
			bw.newLine();
			bw.write("Invoked Methods:");
			bw.newLine();
			
			if (!this.extraMethodsQueue.isEmpty()) {

				do {

					SliceMethod m = this.extraMethodsQueue.remove();
					this.invoked++;

					//System.out.println("this.invoked " + this.invoked);
					if(this.invoked>500){
						break;
					}
					for (String s : m.invokedMethods) {
						//System.out.println("Here abhishek " + s);
						invokedMethod = findMethod(s);
						//System.out.println("Here abhishek 1");
						if (invokedMethod == null) {
							continue;
						}

						this.extraMethodsQueue.add(invokedMethod);
					}

					/*Abhishek modifications */
					Set<String> dup = new HashSet<>();
					dup.addAll(m.sourceCode);
					m.sourceCode.clear();
					m.sourceCode.addAll(dup);
					// Done
					
					for (String x : m.sourceCode) {
						this.extraMethods.add(x);
						bw.write(x);
						bw.newLine();
					//	System.out.println("here " + x);
					}

				} while (!this.extraMethodsQueue.isEmpty());

			}
			//System.out.println("finally out");
			bw.close();
			this.sliceCount++;
		} catch (IOException e) {
			this.logger.warning("Could not save the slice: " + e.getMessage());
		}

	}

	/**
	 * reset data structures for next webview object and slice
	 */
	public void clearSlice() {

		this.slice = new TreeSet<SliceBase>();
		this.sliceStrings = new LinkedList<String>();
		this.extraMethods = new LinkedList<String>();
		this.foundSources = new LinkedList<String>();
		this.trackedSet = new HashSet<SliceVar>();
		this.toSliceSet = new HashSet<SliceVar>();
		this.jsEnabled = false;
		this.usesWebView = false;
		this.injects = false;
		this.currentWebView = null;
		this.annotated = 0;
		this.invoked = 0;

	}

	/**
	 * reads all the source code and converts those to objects for better handling
	 */
	public void prepareSlice() {

		SliceClass currentClass = null;
		SliceMethod currentMethod = null;
		SliceVar currentVar = null;
		SliceControlFlow lastInvoke = null;
		String[] bits;
		Matcher m;
		String tempVar;
		Integer i;

		for (List<String> class_temp : this.classContent) {
			i = 1;
			for (String temp : class_temp) {
				// Regex for Variable/Register search
				m = this.pat.matcher(temp);
				
				if (temp == null || temp.trim().isEmpty()) {
					i++;
					continue;
				}

				if (temp.contains(".class")) {
					// new Class
					bits = temp.split(" ");
					if (bits.length > 0) {
						String className = bits[bits.length - 1];
						currentClass = new SliceClass(temp, className, i);
						this.classMap.put(className, currentClass);
					}
				} else if (temp.contains(".method")) {
					// new Method
					bits = temp.split(" ");
					if (currentClass != null && bits.length > 0) {
						String methodName = bits[bits.length - 1];
						currentMethod = new SliceMethod(temp, currentClass.className, methodName, i);
						currentClass.methodMap.put(methodName, currentMethod);
					}
				} else if (temp.contains(".annotation runtime Landroid/webkit/JavascriptInterface;")) {
					// JavascriptInterface Annotation
					if (currentMethod != null) {
						currentMethod.annotation = true;
						this.annotatedMethods.add(currentMethod);
					}
				} else if (m.find()) {
					// new Variable or new Use of a Variable
					m.reset();
					int n = 0;

					while (m.find()) {
						tempVar = m.group();
						n++;

						// Skip if we're not in a method context
						if (currentMethod == null || currentClass == null) {
							continue;
						}

						// if not in varMap put it in
						if (!currentMethod.varMap.containsKey(tempVar)) {
							currentVar = new SliceVar(tempVar, currentMethod);
							currentMethod.varMap.put(tempVar, currentVar);
						} else {
							currentVar = currentMethod.varMap.get(tempVar);
						}

						// create first varUse or add a new varUse to existing var
						currentVar.createUse(temp, currentClass.className, currentMethod.name, i);

						// if there is a webview object here take the first register and slice for it
						// the first register would be the instance of webview for cases we need to slice
						if ((temp.contains("Landroid/webkit/WebView;") || temp.contains(";->getSettings()Landroid/webkit/WebSettings;") || temp.contains(";->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V")) && n == 1) {
							// save this variable to slice it
							this.toSliceSet.add(currentVar);
						}
					}
				}

				// Control Flow Statements
				if (temp.trim().startsWith("if") || temp.trim().startsWith(":") || temp.trim().startsWith("goto")
						|| temp.trim().startsWith(".catch")) {
					if (currentMethod != null && currentClass != null) {
						currentMethod.cfList.add(new SliceControlFlow(temp, currentClass.className, currentMethod.name, i));
					}
				}

				// move-result needs to add the invoke before
				if (temp.trim().startsWith("move-result")) {
					if (currentMethod != null && lastInvoke != null) {
						currentMethod.cfList.add(lastInvoke);
					}
				}

				// save last invoke (needed for move-results)
				if (temp.trim().startsWith("invoke")) {
					if (currentClass != null && currentMethod != null) {
						lastInvoke = new SliceControlFlow(temp, currentClass.className, currentMethod.name, i);
					}
				}

				// Method Returns
				if (temp.trim().startsWith("return")) {
					if (currentMethod != null && currentClass != null) {
						currentMethod.returnList.add(new SliceControlFlow(temp, currentClass.className, currentMethod.name, i));
					}
				}

				// if in method save the line for later addition to slice
				if (currentMethod != null) {
					currentMethod.sourceCode.add(temp);

					// if it's an invoke line add it to the invokedMethods list
					if (temp.contains("invoke")) {
						currentMethod.invokedMethods.add(temp);
					}

				}

				// if we find .end method then we're not inside a method anymore
				if (temp.trim().contains(".end method")) {
					currentMethod = null;
				}

				i = i + 1; // Line Number

			}
		}

	}

	/**
	 * Uses the prepared slice objects to create a backward slice of the given
	 * position
	 * 
	 * @param className
	 * @param methodName
	 * @param line
	 * @param register
	 */
	public void sliceAt(String className, String methodName, Integer line, String register) {

		Integer current_line;
		SliceVarUse previousUse;
		Matcher m;
		String tempVarName;
		SliceVar tempVar;
		Pattern invokePattern = Pattern.compile("\\s([^->\\s]*)->(.*)");

		// Find and add to slice: Class, Method, Line we shall backward slice
		try{
		SliceClass currentClass = this.classMap.get(className);
		this.slice.add(currentClass);
		SliceMethod currentMethod = currentClass.methodMap.get(methodName);
		this.slice.add(currentMethod);
		SliceVar currentVar = currentMethod.varMap.get(register);
		SliceVarUse currentVarUse = currentVar.varUseMap.get(line);
		this.slice.add(currentVarUse);
		this.registerQueue.add(currentVarUse);
		this.trackedSet.add(currentVar);
		

		do {
			currentVarUse = this.registerQueue.remove();

			// add the current line to slice
			this.slice.add(currentVarUse);

			current_line = currentVarUse.lineNumber;
			currentVar = currentVarUse.sliceVar;

			// Add all controlflow things for the method
			for (SliceControlFlow cf : currentVar.sliceMethod.cfList) {
				this.slice.add(cf);
			}

			currentMethod = currentVar.sliceMethod;
			currentClass = this.classMap.get(currentMethod.className);

			// add class and method lines to slice
			this.slice.add(currentMethod);
			this.slice.add(currentClass);

			previousUse = currentVarUse; // start with the first line (current) then work backwards to previous lines

			while (true) {

				m = this.pat.matcher(previousUse.line);

				while (m.find()) {
					tempVarName = m.group();

					tempVar = currentMethod.varMap.get(tempVarName);

					// if not in trackedSet, add this variable to the set and the queue
					if (!this.trackedSet.contains(tempVar)) {
						this.registerQueue.add(tempVar.varUseMap.get(previousUse.lineNumber));
						this.trackedSet.add(tempVar);
					}
				}

				// if it's an invoke add all returns of the invoked method to the queue
				if (previousUse.line.trim().startsWith("invoke")) {
					m = invokePattern.matcher(previousUse.line);

					if (m.find()) {
						String cname = m.group(1);
						String mname = m.group(2);
						SliceClass invokedClass = this.classMap.get(cname);

						// if it's not a class in our classMap we don't have the source (java lang class
						// for example)
						if (invokedClass != null) {

							SliceMethod invokedMethod = invokedClass.methodMap.get(mname);

							if (invokedMethod != null) {
								// add all return statements to the slice
								for (SliceControlFlow s : invokedMethod.returnList) {
									m = this.pat.matcher(s.line);

									if (m.find()) {
										tempVarName = m.group();
										tempVar = invokedMethod.varMap.get(tempVarName);

										if (!this.trackedSet.contains(tempVar) && tempVar != null) {
											this.registerQueue.add(tempVar.varUseMap.get(s.lineNumber));
											this.trackedSet.add(tempVar);
										}
									}
								}
							}
						}
					}
				}

				// add line to slice
				this.slice.add(previousUse);
				

				current_line = previousUse.lineNumber;

				if (currentVarUse.sliceVar.varUseMap.lowerKey(current_line) != null) {
					previousUse = currentVar.varUseMap.get(currentVarUse.sliceVar.varUseMap.lowerKey(current_line)); // Previous
																														// Use
																														// of
																														// this
																														// Var
				} else {
					break;
				}
			}

		} while (!this.registerQueue.isEmpty());

	}		catch (Exception ex) {
		System.out.println(ex.getMessage());
	}
	}
	/**
	 * Takes a source code line that contains an invoke action and extracts the
	 * SliceMethod object and returns it
	 * 
	 * @param s
	 * @return SliceMethod
	 */
	private SliceMethod findMethod(String s) {

		Matcher m = this.invokePattern.matcher(s);
	//	m.find();
		if(m.find()) {
		String className = "L" + m.group(1);
		String methodName = m.group(2);
		SliceClass sClass = this.classMap.get(className);

		if (sClass == null) {
			return null;
		}
		return sClass.methodMap.get(methodName);
		}	
		return null;
	}

}
