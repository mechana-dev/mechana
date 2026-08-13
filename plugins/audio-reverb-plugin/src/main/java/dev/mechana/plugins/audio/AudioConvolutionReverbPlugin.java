/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * Applies a WAV impulse response to a dry WAV using pure-Java partitioned
 * convolution.
 */
public final class AudioConvolutionReverbPlugin implements TaskPlugin {
	private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("audio-convolution-reverb", "1.0.0");

	@Override
	public PluginDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public void execute(TaskContext context) throws PluginExecutionException {
		Map<String, String> parameters = context.parameters();
		Path scratch = null;
		try {
			scratch = Files.createTempDirectory("mechana-audio-reverb-");
			Path output = scratch.resolve("reverberated.wav");
			var options = new AudioConvolutionProcessor.Options(decimal(parameters, "wet"), decimal(parameters, "dry"),
					decimal(parameters, "preDelayMilliseconds"), bool(parameters, "normalizeIr"),
					bool(parameters, "peakProtection"), decimal(parameters, "headroomDecibels"),
					AudioConvolutionProcessor.DEFAULT_BLOCK_SIZE);
			AudioConvolutionProcessor.Result result = new AudioConvolutionProcessor().process(
					Path.of(required(parameters, "dryPath")), Path.of(required(parameters, "irPath")), output, scratch,
					options, percent -> {
						if (context.isCancellationRequested())
							throw new CancelledException();
						context.reportProgress(percent);
					});
			Path resultMetadata = scratch.resolve("reverb-result.properties");
			Files.writeString(resultMetadata,
					"appliedGain=" + Double.toString(result.appliedGain()) + System.lineSeparator());
			context.publishArtifact("reverberated.wav", output);
			context.publishArtifact("reverb-result.properties", resultMetadata);
		} catch (CancelledException cancelled) {
			throw new PluginExecutionException("Audio convolution was cancelled", cancelled);
		} catch (IOException | RuntimeException failure) {
			throw new PluginExecutionException("Audio convolution failed", failure);
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

	private static double decimal(Map<String, String> parameters, String name) {
		return Double.parseDouble(required(parameters, name));
	}

	private static boolean bool(Map<String, String> parameters, String name) {
		return Boolean.parseBoolean(required(parameters, name));
	}

	private static void deleteTree(Path root) {
		if (root == null)
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// Worker attempt cleanup is authoritative; this is best effort.
		}
	}

	private static final class CancelledException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
