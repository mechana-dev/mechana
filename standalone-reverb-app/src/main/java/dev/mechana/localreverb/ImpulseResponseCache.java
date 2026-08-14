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
import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Persistent content-addressed sample-rate variants of user-selected IR WAVs.
 */
final class ImpulseResponseCache {
	private static final String RESAMPLER_VERSION = "v1";
	private final Path directory;

	ImpulseResponseCache() {
		this(Path.of(System.getProperty("user.home"), "Library", "Caches", "Mechana Reverb", "ir"));
	}

	ImpulseResponseCache(Path directory) {
		this.directory = directory;
	}

	Path prepare(Path source, int targetSampleRate) throws IOException {
		return prepare(source, targetSampleRate, () -> {
		});
	}

	Path prepare(Path source, int targetSampleRate, Runnable cacheMiss) throws IOException {
		Objects.requireNonNull(cacheMiss, "cacheMiss");
		try (WavFile.Reader reader = WavFile.open(source)) {
			if (reader.format().sampleRate() == targetSampleRate)
				return source;
		}
		Files.createDirectories(directory);
		String digest = digest(source);
		Path cached = directory.resolve(stem(source) + "-" + digest.substring(0, 16) + "-" + targetSampleRate + "-"
				+ RESAMPLER_VERSION + ".wav");
		if (valid(cached, targetSampleRate))
			return cached;
		cacheMiss.run();
		Files.deleteIfExists(cached);
		Path temporary = Files.createTempFile(directory, ".ir-", ".wav");
		try {
			DryAudioImporter.resampleWav(source, targetSampleRate, temporary);
			try {
				Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!valid(cached, targetSampleRate))
				throw new IOException("Could not create a valid cached impulse response");
			return cached;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static boolean valid(Path path, int sampleRate) {
		if (!Files.isRegularFile(path))
			return false;
		try (WavFile.Reader reader = WavFile.open(path)) {
			return reader.format().sampleRate() == sampleRate && reader.format().frames() > 0;
		} catch (IOException invalid) {
			return false;
		}
	}

	private static String digest(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] block = new byte[64 * 1024];
				int read;
				while ((read = input.read(block)) >= 0)
					if (read > 0)
						digest.update(block, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String stem(Path path) {
		String name = Objects.requireNonNull(path.getFileName(), "IR filename").toString()
				.replaceFirst("(?i)\\.(?:wav|wave)$", "");
		String safe = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
		return safe.isBlank() ? "ir" : safe;
	}
}
