/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.api.ArtifactReference;
import dev.mechana.coordinator.InMemoryJobMonitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletedJobStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void persistsClientLocalArtifactMetadataWithoutCopyingClientBytes() throws Exception {
		CompletedJobStore store = new CompletedJobStore(temporaryDirectory, new ObjectMapper());
		store.archive(new InMemoryJobMonitor.Snapshot("job-1", "video-ffmpeg", "SUCCEEDED", 100, "1s", 1, 0, 1, 1, "",
				"now", Map.of(), List.of(), List.of()));
		String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		store.registerExternalArtifact("job-1", "movie.mkv",
				new ArtifactReference("client-local", "/Volumes/output/movie.mkv", 123, sha));

		CompletedJobStore reloaded = new CompletedJobStore(temporaryDirectory, new ObjectMapper());
		var artifact = reloaded.artifacts("job-1").stream().filter(item -> item.name().equals("movie.mkv")).findFirst()
				.orElseThrow();
		assertEquals("client-local", artifact.provider());
		assertEquals("/Volumes/output/movie.mkv", artifact.key());
		assertEquals(123, artifact.size());
		assertEquals(sha, artifact.sha256());
		assertFalse(Files.exists(reloaded.artifactsDirectory("job-1").resolve("movie.mkv")));
	}
}
