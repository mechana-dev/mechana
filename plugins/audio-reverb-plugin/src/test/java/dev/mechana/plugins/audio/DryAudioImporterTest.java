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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DryAudioImporterTest {
	@TempDir
	Path temporary;

	@Test
	void recognizesSupportedDryFormats() {
		for (String extension : new String[]{"wav", "wave", "m4a", "aac", "mp4", "aif", "aiff"})
			assertTrue(DryAudioImporter.supports(Path.of("voice." + extension)));
		assertFalse(DryAudioImporter.supports(Path.of("voice.ogg")));
	}

	@Test
	void matchingWavNeedsNoConversion() throws IOException {
		Path input = wav("voice.wav", 48_000, 480);
		assertEquals(input, DryAudioImporter.prepare(input, 48_000, temporary.resolve("converted.wav")));
		assertFalse(java.nio.file.Files.exists(temporary.resolve("converted.wav")));
	}

	@Test
	void resamplesDryWavToIrRate() throws IOException {
		Path input = wav("voice-44k.wav", 44_100, 441);
		Path output = temporary.resolve("voice-48k.wav");
		assertEquals(output, DryAudioImporter.prepare(input, 48_000, output));
		try (WavFile.Reader converted = WavFile.open(output)) {
			assertEquals(48_000, converted.format().sampleRate());
			assertEquals(1, converted.format().channels());
			assertTrue(converted.format().frames() >= 479 && converted.format().frames() <= 481);
		}
	}

	private Path wav(String name, int sampleRate, int frames) throws IOException {
		Path path = temporary.resolve(name);
		try (WavFile.Writer writer = WavFile.create24Bit(path, sampleRate, 1, frames)) {
			for (int index = 0; index < frames; index++)
				writer.writeFrame(new double[]{Math.sin(2 * Math.PI * 440 * index / sampleRate) * 0.25});
		}
		return path;
	}
}
