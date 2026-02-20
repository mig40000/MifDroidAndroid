/**
 * 
 */
package de.potsdam.ApplicationDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Logger;

/**
 * @author abhishektiwari
 *
 */
public class ApplicationDetails {

	private String packageName;
	private String appName;
	private String appPath;
	//private String appPermission;
	public ArrayList<String> permission = new ArrayList<String>();
	private List<String> activityPath;
	private String smaliPath;
	private Map<String, Collection<String>> hmap;
	
	{
		activityPath = new ArrayList<>();
		hmap = new HashMap<>();
	}
	/**
	 * @return the packageName
	 */
	public String getPackageName() {
		return packageName;
	}
	/**
	 * @param packageName the packageName to set
	 */
	public void setPackageName(String packageName) {
		this.packageName = packageName;
	}
	/**
	 * @return the appName
	 */
	public String getAppName() {
		return appName;
	}
	/**
	 * @param appName the appName to set
	 */
	public void setAppName(String appName) {
		
		StringTokenizer st = new StringTokenizer(appName, "/");
		
		while (st.hasMoreTokens()) {  
			appName = st.nextToken();
			if (appName.contains(".apk")){
				appName = appName.replace(".apk", "");
			}		 
		}
		this.appName = appName;
	}
	/**
	 * @return the appPath
	 */
	public String getAppPath() {
		return appPath;
	}
	/**
	 * @param appPath the appPath to set
	 */
	public void setAppPath(String appPath) {
		appPath = appPath.replace(".apk", "");
		this.appPath = appPath;
	}
	/**
	 * @return the activityPath
	 */
	public List<String> getActivityPath() {
		return activityPath;
	}
	/**
	 * @param activityPath the activityPath to set
	 */
	public void setActivityPath(List<String> activityPath) {
		this.activityPath = activityPath;
	}
	/**
	 * @return the smaliPath
	 */
	public String getSmaliPath() {
		return smaliPath;
	}
	/**
	 * @param smaliPath the smaliPath to set
	 */
	public void setSmaliPath(String smaliPath) {
		this.smaliPath = smaliPath;
		this.smaliPath += "/smali/";
	}
	/**
	 * @return the hmap
	 */
	public Map<String, Collection<String>> getHmap() {
		return hmap;
	}
	/**
	 * @param hmap the hmap to set
	 */
	public void setHmap(Map<String, Collection<String>> hmap) {
		this.hmap = hmap;
	}
	
}
