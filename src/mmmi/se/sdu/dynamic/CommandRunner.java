package mmmi.se.sdu.dynamic;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CommandRunner {
	static final class Result {
		final int exitCode;
		final List<String> output;

		Result(int exitCode, List<String> output) {
			this.exitCode = exitCode;
			this.output = output;
		}
	}

	Result run(List<String> command, File workingDir) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command);
		if (workingDir != null) {
			builder.directory(workingDir);
		}
		builder.redirectErrorStream(true);
		Process process = builder.start();
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		}
		int exitCode = process.waitFor();
		return new Result(exitCode, lines);
	}
}

