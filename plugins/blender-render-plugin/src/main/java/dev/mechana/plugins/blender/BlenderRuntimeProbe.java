package dev.mechana.plugins.blender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Detects whether the configured Blender executable can start. */
public final class BlenderRuntimeProbe {
	public record Capabilities(boolean available, String version, String detail) {
	}

	public Capabilities probe(String executable) {
		try {
			Process process = new ProcessBuilder(new BlenderCommands(executable).version()).redirectErrorStream(true)
					.start();
			if (!process.waitFor(Duration.ofSeconds(15))) {
				process.destroyForcibly();
				return new Capabilities(false, "", "Blender version probe timed out");
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			String first = output.lines().findFirst().orElse("");
			return new Capabilities(process.exitValue() == 0, first, output);
		} catch (IOException | InterruptedException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			return new Capabilities(false, "", failure.getMessage());
		}
	}
}
