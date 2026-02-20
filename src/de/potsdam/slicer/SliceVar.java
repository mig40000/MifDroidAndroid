package de.potsdam.slicer;

import java.util.TreeMap;

public class SliceVar {

	public String name;
	public SliceMethod sliceMethod;
	public TreeMap<Integer, SliceVarUse> varUseMap = new TreeMap<Integer, SliceVarUse>();
	
	public SliceVar(String name, SliceMethod sliceMethod) {
		this.name = name;
		this.sliceMethod = sliceMethod;
	}
	
	public SliceVarUse createUse(String temp, String className, String methodName, Integer lineNumber) {
		SliceVarUse s = new SliceVarUse(temp, className, methodName, lineNumber, this);
		this.varUseMap.put(lineNumber , s);
		return s;
	}

}
