/* Copyright (c) 2026 Mark Vita; Licensed under the Apache License, Version 2.0. */
package dev.mechana.plugins.audio;

import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * Creates a convolution-ready impulse response from a known sweep and its wet
 * recorded return.
 */
public final class AudioIrDeconvolutionPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("audio-ir-deconvolution", "1.0.0");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Path scratch = null;
		try {
			scratch = Files.createTempDirectory("mechana-audio-ir-");
			Path output = scratch.resolve("impulse-response.wav");
			Map<String, String> p = context.parameters();
			SweepDeconvolver.Result result = new SweepDeconvolver().deconvolve(Path.of(required(p, "sweepPath")),
					Path.of(required(p, "recordedReturnPath")), output, percent -> {
						if (context.isCancellationRequested())
							throw new CancelledException();
						context.reportProgress(percent);
					});
			Path metadata = scratch.resolve("ir-result.properties");
			Files.writeString(metadata,
					"sampleRate=" + result.sampleRate() + "\nchannels=" + result.channels() + "\nframes="
							+ result.frames() + "\nlatencyMilliseconds=" + result.latencyMilliseconds() + "\npeak="
							+ result.peak() + "\n");
			context.publishArtifact("impulse-response.wav", output);
			context.publishArtifact("ir-result.properties", metadata);
		} catch (CancelledException e) {
			throw new PluginExecutionException("IR generation was cancelled", e);
		} catch (IOException | RuntimeException e) {
			throw new PluginExecutionException("IR generation failed", e);
		} finally {
			deleteTree(scratch);
		}
	}

	private static String required(Map<String, String> parameters, String name) {
		String value = parameters.get(name);
		if (value == null || value.isBlank())
			throw new IllegalArgumentException(name + " is required");
		return value;
	}
	private static void deleteTree(Path root) {
		if (root == null)
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}
	private static final class CancelledException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
