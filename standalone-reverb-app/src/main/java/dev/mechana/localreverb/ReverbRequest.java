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
package dev.mechana.localreverb;

import dev.mechana.plugins.audio.DryAudioImporter;
import java.nio.file.Path;

/**
 * Validated parameters for one local invocation of the production reverb
 * plugin.
 */
public record ReverbRequest(Path dryPath, Path irPath, Path artifactRoot, String outputName, double wet, double dry,
		double preDelayMilliseconds, double lowCutHertz, double highCutHertz, boolean normalizeIr,
		boolean peakProtection, double headroomDecibels) {
	public ReverbRequest {
		dryPath = requiredDryFile(dryPath);
		irPath = requiredWav(irPath, "Impulse-response WAV");
		if (artifactRoot == null)
			throw new IllegalArgumentException("Artifacts folder is required");
		if (outputName == null || outputName.isBlank())
			throw new IllegalArgumentException("Output name is required");
		if (Path.of(outputName).getNameCount() != 1 || !outputName.toLowerCase(java.util.Locale.ROOT).endsWith(".wav"))
			throw new IllegalArgumentException("Output name must be a WAV file name, not a path");
		if (!finiteRange(wet, 0, 2) || !finiteRange(dry, 0, 2) || !finiteRange(preDelayMilliseconds, 0, 10_000)
				|| !finiteRange(lowCutHertz, 0, 20_000) || !finiteRange(highCutHertz, 0, 20_000)
				|| lowCutHertz > 0 && highCutHertz > 0 && lowCutHertz >= highCutHertz
				|| !finiteRange(headroomDecibels, 0, 24))
			throw new IllegalArgumentException("One or more reverb controls are outside the allowed range");
	}

	private static Path requiredDryFile(Path path) {
		if (path == null || !java.nio.file.Files.isRegularFile(path))
			throw new IllegalArgumentException("Dry audio does not exist");
		if (!DryAudioImporter.supports(path))
			throw new IllegalArgumentException("Dry audio must be WAV, M4A, MP3, AIFF, CAF, or FLAC");
		return path.toAbsolutePath().normalize();
	}

	private static Path requiredWav(Path path, String label) {
		if (path == null || !java.nio.file.Files.isRegularFile(path))
			throw new IllegalArgumentException(label + " does not exist");
		Path fileName = path.getFileName();
		if (fileName == null || !fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav"))
			throw new IllegalArgumentException(label + " must be a WAV file");
		return path.toAbsolutePath().normalize();
	}

	private static boolean finiteRange(double value, double minimum, double maximum) {
		return Double.isFinite(value) && value >= minimum && value <= maximum;
	}
}
