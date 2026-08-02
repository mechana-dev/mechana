package dev.mechana.plugins.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Checks whether a worker can safely advertise the OCR capability. */
public final class TesseractRuntimeProbe {
	public Result inspect(String executable, String requiredLanguage) {
		try {
			String version = run(List.of(executable, "--version"));
			String languages = run(List.of(executable, "--list-langs"));
			boolean available = languages.lines().map(String::strip).anyMatch(requiredLanguage::equals);
			return new Result(available, version.lines().findFirst().orElse("tesseract"),
					available ? "ready" : "Missing traineddata language: " + requiredLanguage);
		} catch (IOException | InterruptedException failure) {
			if (failure instanceof InterruptedException)
				Thread.currentThread().interrupt();
			return new Result(false, "unavailable", failure.getMessage());
		}
	}

	private static String run(List<String> command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		if (!process.waitFor(Duration.ofSeconds(10))) {
			process.destroyForcibly();
			throw new IOException("Tesseract probe timed out");
		}
		String output;
		try (var input = process.getInputStream()) {
			output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (process.exitValue() != 0)
			throw new IOException("Tesseract probe failed: " + output.strip());
		return output;
	}

	public record Result(boolean usable, String version, String detail) {
	}
}
