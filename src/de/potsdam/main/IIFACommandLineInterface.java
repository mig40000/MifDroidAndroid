/**
 * 
 */
package de.potsdam.main;

import java.io.File;

import de.potsdam.constants.GenericConstants;

/**
 * @author abhishektiwari
 *
 */

 /** 
  * This class acts as the main entry point
  * for the Analysis phase. 
 
 */
public class IIFACommandLineInterface {


	public static void main(String args[]){

		final long startTime = System.currentTimeMillis();
		try {
			CliOptions options = cliParser(args);
			if(options.showHelp){
				printUsage();
				return;
			}

			ApplicationAnalysis appAnalyzer = new ApplicationAnalysis(options.apkDirectory);
			appAnalyzer.setLogDirectory(options.logDirectory.getAbsolutePath());
			appAnalyzer.extractApplicationDetails();
			System.out.println("Total number of apps analyzed " + ApplicationAnalysis.appCounter);
		} catch (IllegalArgumentException cliException){
			System.err.println("Argument error: " + cliException.getMessage());
			printUsage();
			return;
		} catch (Exception unexpected){
			System.err.println("Unexpected error while running the analysis: " + unexpected.getMessage());
			unexpected.printStackTrace(System.err);
			return;
		} finally {
			final long endTime = System.currentTimeMillis();
			System.out.println("Total execution time in milliseconds: " + (endTime - startTime) );
		}
	}
	
	private static CliOptions cliParser(String args[]){
		String[] safeArgs = args == null ? new String[0] : args;
		CliOptions options = new CliOptions();
		for(int index = 0; index < safeArgs.length; index++){
			String token = safeArgs[index];
			if("-h".equals(token) || "--help".equals(token)){
				options.showHelp = true;
				continue;
			}
			if("-l".equals(token) || "--log-dir".equals(token)){
				if(index + 1 >= safeArgs.length){
					throw new IllegalArgumentException("Missing value for " + token);
				}
				options.logDirectory = new File(safeArgs[++index]);
				continue;
			}
			if(token.startsWith("-")){
				throw new IllegalArgumentException("Unknown option: " + token);
			}
			if(options.apkDirectory != null){
				throw new IllegalArgumentException("Multiple application directories provided: " + options.apkDirectory + ", " + token);
			}
			options.apkDirectory = new File(token);
		}
		if(options.showHelp){
			return options;
		}
		if(options.apkDirectory == null){
			throw new IllegalArgumentException("No application directory supplied.");
		}
		options.apkDirectory = ensureDirectory(options.apkDirectory, "application directory", false);
		File resolvedLogDir = options.logDirectory == null
				? new File(GenericConstants.DEFAULT_LOG_DIRECTORY)
				: options.logDirectory;
		options.logDirectory = ensureDirectory(resolvedLogDir, "log directory", true);
		return options;
	}

	private static File ensureDirectory(File directory, String description, boolean createIfMissing){
		if(directory.exists()){
			if(!directory.isDirectory()){
				throw new IllegalArgumentException("The " + description + " is not a directory: " + directory);
			}
			return directory;
		}
		if(createIfMissing && directory.mkdirs()){
			return directory;
		}
		if(createIfMissing){
			throw new IllegalArgumentException("Unable to create " + description + ": " + directory);
		}
		throw new IllegalArgumentException("The " + description + " does not exist: " + directory);
	}

	private static final class CliOptions {
		private File apkDirectory;
		private File logDirectory;
		private boolean showHelp;
	}
	
	
	private static void printUsage(){
		System.out.println("Usage: java -jar IIFAAnalysis.jar <options> <path of directory containing APK files >");
		System.out.println("-l, --log-dir <directory>  Path to store the log files, default is output/logs");
		System.out.println("-h, --help                 Show this message and exit");
	}
}
