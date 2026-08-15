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
		IrProfileLibrary.Profile named = library.addGenerated(imported, "Scott's Plate Take 1");
		IrProfileLibrary.Profile namedAgain = library.addGenerated(imported, "Scott's Plate Take 1");

		assertTrue(java.nio.file.Files.isRegularFile(first.path()));
		assertTrue(java.nio.file.Files
				.isRegularFile(first.path().resolveSibling(first.path().getFileName() + ".calibration.properties")));
		assertTrue(first.calibrationGain() > 0);
		assertFalse(first.path().equals(imported));
		assertFalse(first.path().equals(second.path()));
		assertEquals("Scott's Plate Take 1.wav", named.path().getFileName().toString());
		assertEquals("Scott's Plate Take 1-2.wav", namedAgain.path().getFileName().toString());
		assertTrue(library.containsName("Scott's Plate Take 1"));
		IrProfileLibrary.Profile replaced = library.addGenerated(imported, "Scott's Plate Take 1", true);
		assertEquals(named.path(), replaced.path());
		assertTrue(library.isFactoryName("small-room"));
		assertThrows(IOException.class, () -> library.addGenerated(imported, "small-room", true));
		library.remove(first);
		assertFalse(java.nio.file.Files.exists(first.path()));
		assertFalse(java.nio.file.Files
				.exists(first.path().resolveSibling(first.path().getFileName() + ".calibration.properties")));
		assertThrows(IOException.class, () -> library.remove(new IrProfileLibrary.Profile("Factory", factoryIr, true)));
	}

	@Test
	void renamesAndDeletesAddedProfilesWithTheirReports() throws Exception {
		Path factory = temporary.resolve("factory-rename");
		Path libraryPath = temporary.resolve("library-rename");
		java.nio.file.Files.createDirectories(factory);
		Path factoryIr = wav(factory.resolve("factory-hall.wav"));
		Path source = wav(temporary.resolve("capture.wav"));
		IrProfileLibrary library = new IrProfileLibrary(libraryPath, factory);
		IrProfileLibrary.Profile added = library.addGenerated(source, "First Capture");
		Path originalReport = added.path().resolveSibling(added.path().getFileName() + ".txt");
		Path originalCalibration = added.path().resolveSibling(added.path().getFileName() + ".calibration.properties");
		java.nio.file.Files.writeString(originalReport, "capture details");

		IrProfileLibrary.Profile renamed = library.rename(added, "Scott Plate");
		Path renamedReport = renamed.path().resolveSibling(renamed.path().getFileName() + ".txt");
		Path renamedCalibration = renamed.path()
				.resolveSibling(renamed.path().getFileName() + ".calibration.properties");
		assertEquals("Scott Plate.wav", renamed.path().getFileName().toString());
		assertFalse(java.nio.file.Files.exists(added.path()));
		assertFalse(java.nio.file.Files.exists(originalReport));
		assertTrue(java.nio.file.Files.isRegularFile(renamed.path()));
		assertEquals("capture details", java.nio.file.Files.readString(renamedReport));
		assertFalse(java.nio.file.Files.exists(originalCalibration));
		assertTrue(java.nio.file.Files.isRegularFile(renamedCalibration));

		IrProfileLibrary.Profile conflicting = library.addGenerated(source, "Existing");
		assertThrows(IOException.class, () -> library.rename(renamed, "Existing"));
		assertThrows(IOException.class,
				() -> library.rename(new IrProfileLibrary.Profile("Factory", factoryIr, true), "Changed"));
		assertTrue(java.nio.file.Files.isRegularFile(conflicting.path()));

		library.remove(renamed);
		assertFalse(java.nio.file.Files.exists(renamed.path()));
		assertFalse(java.nio.file.Files.exists(renamedReport));
		assertFalse(java.nio.file.Files.exists(renamedCalibration));
	}

	private static Path wav(Path path) throws IOException {
		try (WavFile.Writer writer = WavFile.create24Bit(path, 48_000, 1, 1)) {
			writer.writeFrame(new double[]{1});
		}
		return path;
	}
}
