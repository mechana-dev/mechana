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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

	@Test
	void servesGenericInputsAndFencesOutputsByAcceptedLease() throws Exception {
		Path scratch = Files.createTempDirectory("artifact-plane-test-");
		Path input = Files.writeString(scratch.resolve("scene.blend"), "scene", StandardCharsets.UTF_8);
		try (ClientArtifactDataPlane plane = new ClientArtifactDataPlane(scratch, "127.0.0.1")) {
			var reference = plane.serveInput(0, input, "application/octet-stream");
			plane.configureOutputs(1, ".zip");
			HttpClient http = HttpClient.newHttpClient();
			var downloaded = http.send(HttpRequest.newBuilder(URI.create(reference.url())).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(200, downloaded.statusCode());
			assertEquals(reference.sha256(), downloaded.headers().firstValue("X-Checksum-Sha256").orElseThrow());
			for (String lease : java.util.List.of("stale-lease", "accepted-lease")) {
				var uploaded = http.send(
						HttpRequest.newBuilder(URI.create(plane.outputUrl(0))).header("X-Mechana-Lease", lease)
								.PUT(HttpRequest.BodyPublishers.ofString(lease)).build(),
						HttpResponse.BodyHandlers.discarding());
				assertEquals(204, uploaded.statusCode());
			}
			String acceptedHash = HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest("accepted-lease".getBytes(StandardCharsets.UTF_8)));
			assertEquals("accepted-lease", Files.readString(plane.acceptedOutput(0, acceptedHash).path()));
		}
	}
}
