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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.plugins.audio.WavFile;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IrProfileLibraryTest {
	@TempDir
	Path temporary;

	@Test
	void installsFactoryProfilesAndImportsUniqueDurableCopies() throws Exception {
		Path factory = temporary.resolve("factory");
		Path libraryPath = temporary.resolve("library");
		java.nio.file.Files.createDirectories(factory);
		Path factoryIr = wav(factory.resolve("small-room.wav"));
		Path imported = wav(temporary.resolve("My Plate.wav"));
		IrProfileLibrary library = new IrProfileLibrary(libraryPath, factory);

		assertEquals(1, library.profiles().size());
		assertTrue(library.profiles().getFirst().factory());
		IrProfileLibrary.Profile first = library.add(imported);
		IrProfileLibrary.Profile second = library.add(imported);

		assertTrue(java.nio.file.Files.isRegularFile(first.path()));
		assertFalse(first.path().equals(imported));
		assertFalse(first.path().equals(second.path()));
		library.remove(first);
		assertFalse(java.nio.file.Files.exists(first.path()));
		assertThrows(IOException.class, () -> library.remove(new IrProfileLibrary.Profile("Factory", factoryIr, true)));
	}

	private static Path wav(Path path) throws IOException {
		try (WavFile.Writer writer = WavFile.create24Bit(path, 48_000, 1, 1)) {
			writer.writeFrame(new double[]{1});
		}
		return path;
	}
}
