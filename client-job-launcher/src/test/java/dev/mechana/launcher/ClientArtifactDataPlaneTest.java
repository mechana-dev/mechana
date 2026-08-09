/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ClientArtifactDataPlaneTest {
	@Test
	void removesBonjourSuffixFromAutomaticFleetHost() {
		assertEquals("Marks-MacBook-Air-M4", ClientArtifactDataPlane.automaticHost("Marks-MacBook-Air-M4.local"));
		assertEquals("mechana-client", ClientArtifactDataPlane.automaticHost("mechana-client"));
	}

	@Test
	void blankScratchUsesAndCleansTemporaryClientStorage() throws Exception {
		Path scratch;
		try (ClientArtifactDataPlane plane = new ClientArtifactDataPlane(null, "127.0.0.1")) {
			scratch = plane.scratchDirectory();
			assertTrue(Files.isDirectory(scratch));
		}
		assertFalse(Files.exists(scratch));
	}
}
