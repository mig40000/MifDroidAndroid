package mmmi.se.sdu.dynamic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class IntentOverrides {
	static final class Extra {
		final String key;
		final String type;
		final String value;

		Extra(String key, String type, String value) {
			this.key = key;
			this.type = type;
			this.value = value;
		}
	}

	private final Map<String, List<Extra>> overrides = new HashMap<>();

	static IntentOverrides load(Path path) throws IOException {
		IntentOverrides result = new IntentOverrides();
		if (path == null || !Files.exists(path)) {
			return result;
		}
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] parts = trimmed.split("\\|", -1);
			if (parts.length < 4) {
				continue;
			}
			String activity = parts[0].trim();
			String key = parts[1].trim();
			String type = parts[2].trim().toLowerCase(Locale.ROOT);
			String value = parts[3].trim();
			if (activity.isEmpty() || key.isEmpty() || type.isEmpty()) {
				continue;
			}
			result.overrides.computeIfAbsent(activity, k -> new ArrayList<>())
					.add(new Extra(key, type, value));
		}
		return result;
	}

	List<Extra> getExtras(String activity) {
		if (activity == null) {
			return Collections.emptyList();
		}
		return overrides.getOrDefault(activity, Collections.emptyList());
	}
}

