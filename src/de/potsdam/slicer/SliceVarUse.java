package de.potsdam.slicer;

public class SliceVarUse extends SliceBase {
	
	public String methodName;
	public SliceVar sliceVar;
	public String varName;
	
	public SliceVarUse(String line, String className, String methodName, Integer lineNumber, SliceVar sliceVar) {
		this.line = line;
		this.className = className;
		this.methodName = methodName;
		this.lineNumber = lineNumber;
		this.sliceVar = sliceVar;
	}
	
	public SliceVarUse(String className, String methodName, Integer lineNumber, String sliceVar) {
		this.className = className;
		this.methodName = methodName;
		this.lineNumber = lineNumber;
		this.varName = sliceVar;
	}

}
