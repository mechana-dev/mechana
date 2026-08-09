/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mechana.protocol.Messages.ArtifactReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientVideoAssemblyTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void rejectsAndRemovesDownloadedContentThatDoesNotMatchArtifactIdentity() throws Exception {
		Path segment = temporaryDirectory.resolve("segment.mkv");
		Files.writeString(segment, "tampered");
		ArtifactReference expected = new ArtifactReference("server-local", "segments/one", 8, "/segment", false,
				"0000000000000000000000000000000000000000000000000000000000000000");

		assertThrows(IOException.class, () -> ClientVideoAssembly.verify(segment, expected));
		assertFalse(Files.exists(segment));
	}
}
