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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.plugins.audio.WavFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpulseResponseCacheTest {
	@TempDir
	Path temporary;

	@Test
	void reusesContentAddressedRateVariantAndInvalidatesChangedSource() throws Exception {
		Path source = temporary.resolve("room.wav");
		write(source, new double[]{1, 0.5, 0.25, 0});
		ImpulseResponseCache cache = new ImpulseResponseCache(temporary.resolve("cache"));
		java.util.concurrent.atomic.AtomicInteger cacheMisses = new java.util.concurrent.atomic.AtomicInteger();

		Path first = cache.prepare(source, 44_100, cacheMisses::incrementAndGet);
		Path reused = cache.prepare(source, 44_100, cacheMisses::incrementAndGet);
		assertEquals(first, reused);
		assertEquals(1, cacheMisses.get());
		try (WavFile.Reader reader = WavFile.open(first)) {
			assertEquals(44_100, reader.format().sampleRate());
		}

		write(source, new double[]{1, -0.5, 0.25, 0});
		Path changed = cache.prepare(source, 44_100);
		assertNotEquals(first, changed);
	}

	@Test
	void clearsOnlyRegularCacheEntriesWhenApplicationBuildChanges() throws Exception {
		Path cacheDirectory = temporary.resolve("versioned-cache");
		ImpulseResponseCache cache = new ImpulseResponseCache(cacheDirectory);
		cache.resetForBuild("build-one");
		Path cached = java.nio.file.Files.writeString(cacheDirectory.resolve("old.wav"), "cached");
		Path retainedDirectory = java.nio.file.Files.createDirectory(cacheDirectory.resolve("unexpected-directory"));

		cache.resetForBuild("build-one");
		assertTrue(java.nio.file.Files.exists(cached));
		cache.resetForBuild("build-two");

		assertFalse(java.nio.file.Files.exists(cached));
		assertTrue(java.nio.file.Files.isDirectory(retainedDirectory));
		assertEquals("build-two", java.nio.file.Files.readString(cacheDirectory.resolve(".application-build")).strip());
	}

	private static void write(Path path, double[] samples) throws Exception {
		try (WavFile.Writer writer = WavFile.create24Bit(path, 48_000, 1, samples.length)) {
			for (double sample : samples)
				writer.writeFrame(new double[]{sample});
		}
	}
}
