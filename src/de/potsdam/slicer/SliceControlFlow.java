package de.potsdam.slicer;

public class SliceControlFlow extends SliceBase {
	
	public String methodName;
	
	public SliceControlFlow(String line, String className, String methodName, Integer lineNumber) {
		this.line = line;
		this.className = className;
		this.methodName = methodName;
		this.lineNumber = lineNumber;
	}

}
