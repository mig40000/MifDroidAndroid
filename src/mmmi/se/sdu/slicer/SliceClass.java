package mmmi.se.sdu.slicer;

import java.util.HashMap;
import java.util.Map;

public class SliceClass extends SliceBase {

	public Map<String, SliceMethod> methodMap = new HashMap<String, SliceMethod>();
	
	public SliceClass(String line, String className, Integer lineNumber) {
		this.line = line;
		this.className = className;
		this.lineNumber = lineNumber;
	}

}
