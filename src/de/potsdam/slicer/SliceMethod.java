package de.potsdam.slicer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SliceMethod extends SliceBase {
	
	public String name;
	public Boolean annotation = false;
	public Map<String, SliceVar> varMap = new HashMap<String, SliceVar>();
	public ArrayList<SliceControlFlow> cfList = new ArrayList<SliceControlFlow>();
	public ArrayList<SliceControlFlow> returnList = new ArrayList<SliceControlFlow>();
	public ArrayList<String> sourceCode = new ArrayList<String>();
	public ArrayList<String> invokedMethods = new ArrayList<String>();

	public SliceMethod(String line, String className, String name, Integer lineNumber) {
		this.line = line;
		this.className = className;
		this.name = name;
		this.lineNumber = lineNumber;
	}

}
