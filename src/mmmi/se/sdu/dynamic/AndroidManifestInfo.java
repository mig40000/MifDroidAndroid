package mmmi.se.sdu.dynamic;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

final class AndroidManifestInfo {
	final String packageName;
	final List<String> activities;
	final String launcherActivity;

	private AndroidManifestInfo(String packageName, List<String> activities, String launcherActivity) {
		this.packageName = packageName;
		this.activities = activities;
		this.launcherActivity = launcherActivity;
	}

	static AndroidManifestInfo parse(String manifestPath) throws IOException, XmlPullParserException {
		try (InputStream file = new FileInputStream(manifestPath)) {
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
			XmlPullParser parser = factory.newPullParser();
			parser.setInput(file, null);

			String pkg = null;
			List<String> activities = new ArrayList<>();
			String currentActivity = null;
			boolean hasMain = false;
			boolean hasLauncher = false;
			String launcherActivity = null;

			int event = parser.getEventType();
			while (event != XmlPullParser.END_DOCUMENT) {
				String tag = parser.getName();
				if (event == XmlPullParser.START_TAG) {
					if ("manifest".equals(tag)) {
						pkg = parser.getAttributeValue(null, "package");
					} else if ("activity".equals(tag)) {
						String name = parser.getAttributeValue(null, "android:name");
						if (name == null) {
							name = parser.getAttributeValue(null, "name");
						}
						currentActivity = normalizeActivityName(pkg, name);
						if (currentActivity != null) {
							activities.add(currentActivity);
						}
						hasMain = false;
						hasLauncher = false;
					} else if ("action".equals(tag)) {
						String action = parser.getAttributeValue(null, "android:name");
						if ("android.intent.action.MAIN".equals(action)) {
							hasMain = true;
						}
					} else if ("category".equals(tag)) {
						String category = parser.getAttributeValue(null, "android:name");
						if ("android.intent.category.LAUNCHER".equals(category)) {
							hasLauncher = true;
						}
					}
				} else if (event == XmlPullParser.END_TAG) {
					if ("activity".equals(tag)) {
						if (currentActivity != null && hasMain && hasLauncher && launcherActivity == null) {
							launcherActivity = currentActivity;
						}
						currentActivity = null;
					}
				}

				event = parser.next();
			}

			return new AndroidManifestInfo(pkg, activities, launcherActivity);
		}
	}

	private static String normalizeActivityName(String pkg, String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		if (name.startsWith(".")) {
			return pkg + name;
		}
		if (!name.contains(".")) {
			return pkg + "." + name;
		}
		return name;
	}
}

