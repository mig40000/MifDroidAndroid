/**
 * 
 */
package de.potsdam.extract;

import java.io.File;
import java.util.ArrayList;

/**
 * @author abhishektiwari
 *
 */
public class InputApkFileContainer {
	
	private ArrayList<File> inputApplicationFiles;
	
	public  InputApkFileContainer(File inputDirectory){

		this.inputApplicationFiles =  new ArrayList<File>();
		this.inputApplicationFiles.addAll(ExtractFilesFromDirectory.collectFiles(inputDirectory, new String[]{"apk"}));
		
	}

	/**
	 * @return the inputApplicationFiles
	 */
	public ArrayList<File> getInputApplicationFiles() {
		return inputApplicationFiles;
	}

	/**
	 * @param inputApplicationFiles the inputApplicationFiles to set
	 */
	public void setInputApplicationFiles(ArrayList<File> inputApplicationFiles) {
		this.inputApplicationFiles = inputApplicationFiles;
	}
}
