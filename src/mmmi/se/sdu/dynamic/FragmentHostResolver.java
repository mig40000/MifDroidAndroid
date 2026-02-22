package mmmi.se.sdu.dynamic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FragmentHostResolver {
	private FragmentHostResolver() {
	}

	static Map<String, Set<String>> findHostActivities(Path decodedDir, Set<String> fragmentClasses, List<String> manifestActivities)
			throws IOException {
		Map<String, Set<String>> result = new HashMap<>();
		if (fragmentClasses == null || fragmentClasses.isEmpty()) {
			return result;
		}
		Set<String> activitySet = new HashSet<>(manifestActivities);
		Path smaliRoot = decodedDir.resolve("smali");
		if (!Files.exists(smaliRoot)) {
			return result;
		}

		Files.walk(smaliRoot)
				.filter(path -> path.toString().endsWith(".smali"))
				.forEach(path -> {
					try {
						byte[] bytes = Files.readAllBytes(path);
						String content = new String(bytes, StandardCharsets.UTF_8);
						String className = extractClassName(content);
						if (className == null || !activitySet.contains(className)) {
							return;
						}
						for (String fragment : fragmentClasses) {
							String smaliRef = "L" + fragment.replace('.', '/') + ";";
							if (content.contains(smaliRef)) {
								result.computeIfAbsent(fragment, k -> new HashSet<>()).add(className);
							}
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});

		return result;
	}

	private static String extractClassName(String content) {
		int idx = content.indexOf(".class ");
		if (idx < 0) {
			return null;
		}
		int lineEnd = content.indexOf('\n', idx);
		if (lineEnd < 0) {
			lineEnd = content.length();
		}
		String line = content.substring(idx, lineEnd).trim();
		int lastSpace = line.lastIndexOf(' ');
		if (lastSpace < 0) {
			return null;
		}
		String raw = line.substring(lastSpace + 1);
		if (!raw.startsWith("L") || !raw.endsWith(";")) {
			return null;
		}
		return raw.substring(1, raw.length() - 1).replace('/', '.');
	}
}
