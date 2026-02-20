/**
 * 
 */
package de.potsdam.Logging;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * @author abhishektiwari
 *
 */
public class IIFALogger {

	private Logger logger;
	
	public void initLogging(String appName, String loggingDirectory){
		
		String logFile = loggingDirectory + appName + ".log";
		
		this.logger = Logger.getLogger(logFile);
		this.logger.setUseParentHandlers(false);
		
		try {
			FileHandler	logFileHandler = new FileHandler(logFile);
			this.logger.addHandler(logFileHandler);
			SimpleFormatter formatter = new SimpleFormatter();
			logFileHandler.setFormatter(formatter);
		} catch (SecurityException | IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	/**
	 * @return the logger
	 */
	public Logger getLogger() {
		return logger;
	}

	/**
	 * @param logger the logger to set
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}
}
