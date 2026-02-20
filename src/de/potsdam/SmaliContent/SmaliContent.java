/**
 * 
 */
package de.potsdam.SmaliContent;

import java.util.ArrayList;
import java.util.List;

import de.potsdam.constants.GenericConstants;

/**
 * @author abhishektiwari
 *
 */
public class SmaliContent {

	//classContent contains smali code for each class 
	public List<List<String>> classContent;
	
	{
		classContent = new ArrayList<>(GenericConstants.MAX_CLASS);
	}
}
