package mmmi.se.sdu.slicer;

abstract public class SliceBase implements Comparable<SliceBase>{

	public String line;
	public Integer lineNumber;
	public String className;
	
	public int compareTo(SliceBase s) {
		int i = this.className.compareTo(s.className);
		if (i != 0) return i;
		
		return Integer.compare(this.lineNumber, s.lineNumber);
	}

}
