/**
 * 
 */
package de.potsdam.extract;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FileUtils;

/**
 * @author abhishektiwari
 *
 */
public class ExtractFilesFromDirectory {

	public static List<File> collectFiles(File directory, String[] extensions) {
		ArrayList<File> files = new ArrayList<File>();

	//	System.out.println("Directoy is " + directory);
		if (directory.isDirectory()) {
		    try {

		         boolean recursive = true;
		         
		         Collection<File> foundApks = FileUtils.listFiles(directory, extensions, recursive);
		      //   System.out.println("Number of classes " + foundApks.size());
		         for (Iterator<File> iterator = foundApks.iterator(); iterator.hasNext();) {
		             files.add(iterator.next());
		         }   
		     } catch (Exception e) {
		         e.printStackTrace();
		     }   
		}

		return files;
	}
}
