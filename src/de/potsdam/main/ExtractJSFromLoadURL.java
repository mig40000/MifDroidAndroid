package de.potsdam.main;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ThreadLocalRandom;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements; 

import de.potsdam.constants.GenericConstants;
import de.potsdam.extract.ExtractFilesFromDirectory;

public class ExtractJSFromLoadURL {
	
	public static void getDBDetails() {
		
		Connection c = null;
		Statement stmt = null;
		String sql = "select DISTINCT appName from webview_prime;";
		
		try {
			Class.forName("org.sqlite.JDBC");
			c = DriverManager.getConnection(GenericConstants.DB_NAME);
			c.setAutoCommit(false);
			
			stmt = c.createStatement();
			
			ResultSet rs = stmt.executeQuery(sql);
			
			while(rs.next()) {
				
			//	System.out.println(rs.getString("appName"));
				extractloadURL(rs.getString("appName"));
			}
			
		} catch (ClassNotFoundException | SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	public static void extractloadURL(String appname) throws IOException {
		
		String folderpath = "output/" + appname;
		String[] filenameString = new String[2];
		
		File dir = new File(folderpath);
		ArrayList<File> inputSmaliFiles =  new ArrayList<File>();
		
		inputSmaliFiles.addAll(ExtractFilesFromDirectory.collectFiles(dir, new String[]{"smali"}));
		
		List<String> lines = new ArrayList();
		Path path;
		
		for(File smaliFile: inputSmaliFiles) {
			//System.out.println(smaliFile.getAbsolutePath());
			//System.out.println(smaliFile.getAbsolutePath());
			path = Paths.get(smaliFile.getAbsolutePath());
			lines = Files.readAllLines(path);
			filenameString = getJSorHTML(lines);
			findFile(appname, filenameString[0], filenameString[1]);
		
		}
		
	}

	public static void findFile(String appName, String filenameString, String interfaceObject) throws IOException{
		String dirPath = "output/intermediate/" + appName + "/assets/"; 
		String absolutFileName = "";
		File f = new File(dirPath);
		List<String> lines = new ArrayList();
		
		if(filenameString != null && filenameString.contains("file://") && filenameString.contains("android_asset")){
		//	System.out.println("appname is " + appName + " filename is: " + filenameString);
			StringTokenizer st  = new StringTokenizer(filenameString, "/");
			while(st.hasMoreTokens()){
				absolutFileName = st.nextToken();
			}
			dirPath = dirPath+absolutFileName;
			File htmlFile = new File(dirPath);

			if(htmlFile.exists()){
				Path path = Paths.get(htmlFile.getAbsolutePath());
				lines = Files.readAllLines(path);
				Document doc = Jsoup.parse(htmlFile);  
				String title = doc.title(); 
				Elements jsScripElements = doc.getElementsByTag("script");
				for(Element jsScripElement: jsScripElements){
				//	System.out.println("JSValue is " + jsScripElement.data());
					if(!jsScripElement.data().isEmpty())
						storeJSFromHtml(appName, jsScripElement.data(), interfaceObject);

				}
			}
		}

	}
	public static void storeJSFromHtml(String appName, String JSValue, String interfaceObject){
		String outputPath = "JSCodeHtml/" + appName + "_" + ThreadLocalRandom.current().nextInt(1, 100 + 1);
		if(interfaceObject!=null){
			outputPath = outputPath + "#" + interfaceObject + ".js";
		}
		else{
			outputPath = outputPath + ".js";
		}
		File jsfile = new File(outputPath);

		Path path = Paths.get(jsfile.getAbsolutePath());
		try {
			Files.write(path, JSValue.getBytes());
		} catch (IOException e) {
			e.printStackTrace();
		}
		


	}

	
	public static String[] getJSorHTML(List<String> lines) {
		
		String[] fileArray = lines.toArray(new String[0]);
		int index = 0;
		String register = "";
		String[] externalJS = new String[2];
		String interfaceObject = null;
		while(index < fileArray.length) {
			if(fileArray[index].contains("WebView;->loadUrl(")) {
			//	System.out.println(fileArray[index]);
				register = getRegister(fileArray[index]);
				externalJS[0] = getJSFile(fileArray, index, register);
				externalJS[1] = getInterfaceObject(fileArray, index);
			}
			index++;
		}
		return externalJS;
	}

	public static String getInterfaceObject(String[] fileArray, int index){
		String interfaceObject = null;

		while(index > 0){
			index--;
			if(fileArray[index].contains("WebView;->addJavascriptInterface(")){
				StringTokenizer st = new StringTokenizer(fileArray[index], ",}");
				st.nextToken();
				st.nextToken();
				String register = st.nextToken();
				while(index > 0){
					index--;
					if(fileArray[index].contains("const-string") && fileArray[index].contains(register)){
						interfaceObject = fileArray[index].replace("const-string", "");
						interfaceObject = interfaceObject.replace(register, "");
						interfaceObject = interfaceObject.replace(",", "");
						interfaceObject = interfaceObject.replace("\"", "");
						interfaceObject = interfaceObject.trim();
						break;
					}
				}

			}
		
		}

		return interfaceObject;
	}
	
	public static String getRegister(String line) {
		
		String register = "";
		StringTokenizer st = new StringTokenizer(line, ",}");
		st.nextToken();
		register = st.nextToken();
		return register;

	}

	public static String getJSFile(String[] fileArray, int index, String register){
		String[] jsFile = new String[2];
		while(index > 0){
			index--;
			if(fileArray[index].contains("const-string") && fileArray[index].contains(register)){
				jsFile = fileArray[index].split(",");
				jsFile[1] = jsFile[1].trim();
				jsFile[1] = jsFile[1].replace("\"", "");
				break;
			}
		}

		//System.out.println(jsFile[1]);
		return jsFile[1];
	}
}
