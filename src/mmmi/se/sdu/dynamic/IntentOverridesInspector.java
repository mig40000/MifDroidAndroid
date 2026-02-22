package mmmi.se.sdu.dynamic;

import java.nio.file.Path;

public final class IntentOverridesInspector {
	public static void main(String[] args) throws Exception {
		Path path = args.length > 0 ? java.nio.file.Paths.get(args[0]) : java.nio.file.Paths.get("output/dynamic/intent-overrides.txt");
		IntentOverrides overrides = IntentOverrides.load(path);
		System.out.println("Loaded intent overrides from: " + path);
		if (overrides.getExtras(null).isEmpty()) {
			System.out.println("No overrides found (check format or path).");
		}
	}
}
