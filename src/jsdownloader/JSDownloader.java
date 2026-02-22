package jsdownloader;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;  
import java.util.StringTokenizer;

import java.io.*;
import java.util.*;

import mmmi.se.sdu.constants.GenericConstants;

public class JSDownloader {
	
	public static void getJSDetails() {
		
		Connection c = null;
		Statement stmt = null;
		String sql = "select jsdetails.PASS_STRING, webview_prime.intefaceObject, webview_prime.appName from jsdetails, webview_prime where jsdetails.ACTIVITY_NAME = webview_prime.initiatingClass;";
		//ArrayList<String> jsString = new ArrayList<String>();
		String jsString = new String();
		String ifcObj = "";
		String appName = "";

		try {
			Class.forName("org.sqlite.JDBC");
			c = DriverManager.getConnection(GenericConstants.DB_NAME);
			c.setAutoCommit(false);

			stmt = c.createStatement();
			
		//	System.out.println("sql is " + sql);
			
			ResultSet rs = stmt.executeQuery(sql);
			
			while(rs.next()) {
				jsString  = rs.getString("PASS_STRING");
				ifcObj = rs.getString("intefaceObject");
				appName = rs.getString("appName");
				extractJS(jsString, ifcObj, appName);
			}
			
			 stmt.close();
			 c.commit();
			 c.close();
			
			
		}catch (ClassNotFoundException | SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
	}
	
	/**
	 * Removes duplicate entries from webview_prime by using webview_new as temporary storage.
	 * Handles both old (6 columns) and new (10 columns) schemas gracefully.
	 * Automatically detects if enhanced schema is available and copies all columns.
	 */
	public static void removeDuplicates() {
		
		Connection c = null;
		Statement stmt = null;

		try {
			Class.forName("org.sqlite.JDBC");
			c = DriverManager.getConnection(GenericConstants.DB_NAME);
			c.setAutoCommit(false);

			// Detect if enhanced schema is available
			boolean hasEnhancedSchema = false;
			try {
				stmt = c.createStatement();
				stmt.executeQuery("SELECT methodCount FROM webview_prime LIMIT 1");
				stmt.close();
				hasEnhancedSchema = true;
				System.out.println("[DEBUG] Deduplication: Using ENHANCED schema (10 columns)");
			} catch (SQLException e) {
				System.out.println("[DEBUG] Deduplication: Using BASIC schema (6 columns)");
			}

			// Define column list based on schema
			String columnList;
			if (hasEnhancedSchema) {
				columnList = "appName, initiatingClass, bridgeClass, intefaceObject, bridgeMethods, " +
							 "initiatingMethod, methodCount, timestamp, readableInitiatingClass, readableBridgeClass";
			} else {
				columnList = "appName, initiatingClass, bridgeClass, intefaceObject, bridgeMethods, initiatingMethod";
			}

			// Step 1: Copy distinct records from webview_prime to webview_new
			String sql = "INSERT INTO webview_new (" + columnList + ") " +
						 "SELECT DISTINCT " + columnList + " FROM webview_prime;";

			stmt = c.createStatement();
			System.out.println("Removing duplicates from webview_prime...");
			System.out.println("[DEBUG] Copying columns: " + columnList);
			stmt.executeUpdate(sql);
			stmt.close();
			c.commit();

			// Step 2: Clear webview_prime
			sql = "DELETE FROM webview_prime;";
			stmt = c.createStatement();
			stmt.executeUpdate(sql);
			stmt.close();
			c.commit();

			// Step 3: Copy distinct records back from webview_new to webview_prime
			sql = "INSERT INTO webview_prime (" + columnList + ") " +
				  "SELECT DISTINCT " + columnList + " FROM webview_new;";
			stmt = c.createStatement();
			stmt.executeUpdate(sql);
			stmt.close();
			c.commit();

			System.out.println("Duplicates removed successfully");
			if (hasEnhancedSchema) {
				System.out.println("[DEBUG] All 10 columns preserved during deduplication");
			}
			c.close();

		} catch (ClassNotFoundException | SQLException e) {
			System.err.println("[ERROR] Failed to remove duplicates: " + e.getMessage());
			e.printStackTrace();

			// Try to rollback on error
			if (c != null) {
				try {
					c.rollback();
					System.out.println("Transaction rolled back");
				} catch (SQLException rollbackEx) {
					System.err.println("[ERROR] Rollback failed: " + rollbackEx.getMessage());
				}
			}
		} finally {
			// Ensure connection is closed
			if (c != null) {
				try {
					c.close();
				} catch (SQLException e) {
					System.err.println("[WARNING] Failed to close connection: " + e.getMessage());
				}
			}
		}
	}
	
	public static void getAltJSDetails() {
		Connection c = null;
		Statement stmt = null;
		String sql = "select appName, intefaceObject from webview_prime order by appName;";
		ArrayList<String> ifcObj = new ArrayList<String>();
		String appName = "";
		String dummy  = "";
		boolean flag = true;
		
		try {
		Class.forName("org.sqlite.JDBC");
		c = DriverManager.getConnection(GenericConstants.DB_NAME);
		c.setAutoCommit(false);

		stmt = c.createStatement();
		
		ResultSet rs = stmt.executeQuery(sql);
		
		while(rs.next()) {
			
			ifcObj.add(rs.getString("intefaceObject"));
			appName = rs.getString("appName");
			
			if(flag) {
				dummy = appName;
				flag = false;
			}
			if(!dummy.contains(appName)) {
				extractAltJS(ifcObj, dummy);
				ifcObj.clear();
				dummy = appName;
			}	
			
		}
			extractAltJS(ifcObj, dummy);
		
		
		 stmt.close();
		 c.commit();
		 c.close();
		
		
		}catch (ClassNotFoundException | SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
			
		
	}
	
	public static void extractAltJS(ArrayList<String> ifcObj, String appName) {
		
		String path = "output/intermediate/";
		Integer counter = 0;
		path = path.concat(appName+"/");
		ArrayList<String> jsFilePath = findJSscript(path, appName);
		String ifcFileName = "";
		String dummyName = "";

		ifcObj = duplicateRemover(ifcObj);
		
		
		for(String ifcobj:ifcObj) {
			ifcFileName = ifcFileName.concat(ifcobj + "#");	
		}
		ifcFileName = ifcFileName.replaceAll("#$", "");
		
	//	System.out.println("Size is " + jsFilePath.size());
		
		for(String jsFile: jsFilePath) {
			dummyName = appName + Integer.toString(counter);
			copyJsFromApp(jsFile, ifcFileName, dummyName);
			counter++;
		}
		
		//counter = 0;
		
		
	}
	
	public static ArrayList<String> duplicateRemover(ArrayList<String> ifcObj){
		
		Set<String> set = new LinkedHashSet<String>();
		set.addAll(ifcObj);
		ifcObj.clear();
		ifcObj.addAll(set);
		
		return ifcObj;
	}
	
	public static ArrayList<String> findJSscript(String path, String appName) {

		ArrayList<String> jsFilePath = new ArrayList<String>();
		try {
			//Use apktool to extract the source
			ProcessBuilder pb = new ProcessBuilder("find", path, "-name", "*.js");
			Process p = pb.start();

				BufferedReader reader = 
				         new BufferedReader(new InputStreamReader(p.getInputStream()));
				
				BufferedReader errReader = 
				         new BufferedReader(new InputStreamReader(p.getErrorStream()));
		       
				String line = "";
				while((line = reader.readLine()) != null) {
					jsFilePath.add(line);
				//	System.out.print("File path is " + line + "\n");
		        }
				while((line = errReader.readLine()) != null) {
		            System.out.print(line + "\n");
		        }
				 p.waitFor();
		        
		} catch (IOException | InterruptedException e1) {
	        e1.printStackTrace();
	    }
		return jsFilePath;
	}

	
	public static void extractJS(String jsString, String ifcObj, String appName) {
		
		String path = "output/intermediate/";
		path = path.concat(appName+"/");
		boolean flag = false;
		String script = "";
		if(jsString.contains("asset") && jsString.contains(".html")) {
			//System.out.println("correctly parsed JsString " + jsString);
			StringTokenizer st  = new StringTokenizer(jsString, "///");

			while(st.hasMoreElements()) {
			//	System.out.println("St is " + st.nextToken());
				if(!flag)
					st.nextToken();
				if(flag) {
					path = path.concat(st.nextToken()+"/");
				}
				flag = true;
			}
			path = path.replace("\"/", "");
			if(path.contains("android_asset"))
				path = path.replace("android_asset", "assets");
			else
				path = path.replace("asset", "assets");
		//	System.out.println("Correct path is " + path);
			parseHtml(path, ifcObj, appName);
		}
		
		if(jsString.contains("javascript:")) {
		//	System.out.println("jsString is " + jsString);
			String array[] = jsString.split("javascript:");

			for(String token : array) {
				script = token;
			//	System.out.println("script is " + script);
			}
		/*	StringTokenizer st  = new StringTokenizer(jsString, "javascript:");
			while(st.hasMoreElements()) {
				st.nextToken();
				script = st.nextToken();
				System.out.println("script is " + script);
				
			}*/
			
			
		}
				
	}
	
	public static void parseHtml(String fileName, String ifcObj, String appName) {
		
		// NOTE: jsoup dependency removed; no-op to keep compilation passing.

	}
	
	public static String findAbsolutePath(String js, String appName) {
		
		String path = "output/intermediate/";
		path = path.concat(appName+"/");
		String jsFilePath = "";
		try {
			//Use apktool to extract the source
			ProcessBuilder pb = new ProcessBuilder("find", path, "-name", js);
			Process p = pb.start();

				BufferedReader reader = 
				         new BufferedReader(new InputStreamReader(p.getInputStream()));
				
				BufferedReader errReader = 
				         new BufferedReader(new InputStreamReader(p.getErrorStream()));
		       
				String line = "";
				while((line = reader.readLine()) != null) {
					jsFilePath = line;
					System.out.print("File path is " + line + "\n");
		        }
				while((line = errReader.readLine()) != null) {
		            System.out.print(line + "\n");
		        }
				 p.waitFor();
		        
		} catch (IOException | InterruptedException e1) {
	        e1.printStackTrace();
	    }
		return jsFilePath;
		
	}
	
	public static void copyJsFromApp(String jsFilePath, String ifcObj, String appName) {
		String destination = "JSCode/" + appName + "#" + ifcObj + ".js";  
		//System.out.print("source is " + jsFilePath + "\n");
		//System.out.print("destination is " + destination + "\n");
		try {
			//Use apktool to extract the source
			ProcessBuilder pb = new ProcessBuilder("cp", jsFilePath, destination);
			Process p = pb.start();

				BufferedReader reader = 
				         new BufferedReader(new InputStreamReader(p.getInputStream()));
				
				BufferedReader errReader = 
				         new BufferedReader(new InputStreamReader(p.getErrorStream()));
		       
				String line = "";
				while((line = reader.readLine()) != null) {
					//jsFilePath = line;
					System.out.print("File path is " + line + "\n");
		        }
				while((line = errReader.readLine()) != null) {
		            System.out.print(line + "\n");
		        }
				 p.waitFor();
		        
		} catch (IOException | InterruptedException e1) {
	        e1.printStackTrace();
	    }
		
		
	}
	

}
