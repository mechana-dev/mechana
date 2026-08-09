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

package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.api.ArtifactReference;
import dev.mechana.api.ArtifactStore;
import dev.mechana.api.StorageSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable terminal-job snapshots and server-owned downloadable artifacts. */
final class CompletedJobStore {
	record Artifact(String name, long size, String provider, String key, String sha256) {
	}

	private static final String SNAPSHOT_FILE = "snapshot.json";
	private static final String ARTIFACTS_DIRECTORY = "artifacts";
	private static final String EXTERNAL_ARTIFACTS_FILE = ".external-artifacts.json";
	private final Path jobsRoot;
	private final ObjectMapper json;
	private final ArtifactStore artifactStore;
	private final Map<String, InMemoryJobMonitor.Snapshot> snapshots = new LinkedHashMap<>();

	CompletedJobStore(Path dataDirectory, ObjectMapper json) throws IOException {
		this(dataDirectory, json, new ServerLocalArtifactStore(dataDirectory));
	}

	CompletedJobStore(Path dataDirectory, ObjectMapper json, ArtifactStore artifactStore) throws IOException {
		this.jobsRoot = dataDirectory.toAbsolutePath().normalize().resolve("jobs");
		this.json = json;
		this.artifactStore = artifactStore;
		Files.createDirectories(jobsRoot);
		load();
	}

	synchronized void archive(InMemoryJobMonitor.Snapshot snapshot) throws IOException {
		if (!isTerminal(snapshot.stage()))
			throw new IllegalArgumentException("Only terminal jobs can be archived: " + snapshot.jobId());
		Path jobDirectory = jobDirectory(snapshot.jobId());
		Path artifacts = jobDirectory.resolve(ARTIFACTS_DIRECTORY);
		Files.createDirectories(artifacts);
		writeAtomically(jobDirectory.resolve(SNAPSHOT_FILE), snapshot);
		byte[] summary = json.writeValueAsBytes(snapshot);
		try (InputStream bytes = new java.io.ByteArrayInputStream(summary)) {
			ArtifactReference published = artifactStore.put("jobs/" + snapshot.jobId() + "/artifacts/job-summary.json",
					bytes);
			if (published.sizeBytes() != summary.length)
				throw new IOException("Completed summary publication size mismatch");
		}
		snapshots.put(snapshot.jobId(), snapshot);
	}

	synchronized Optional<InMemoryJobMonitor.Snapshot> find(String jobId) {
		return Optional.ofNullable(snapshots.get(jobId));
	}

	synchronized List<InMemoryJobMonitor.Snapshot> snapshots() {
		List<InMemoryJobMonitor.Snapshot> result = new ArrayList<>(snapshots.values());
		java.util.Collections.reverse(result);
		return List.copyOf(result);
	}

	synchronized List<Artifact> artifacts(String jobId) throws IOException {
		requireKnown(jobId);
		Path root = jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY);
		if (!Files.isDirectory(root))
			return List.of();
		List<Artifact> artifacts = new ArrayList<>();
		try (var files = Files.walk(root)) {
			artifacts.addAll(files.filter(Files::isRegularFile).filter(
					file -> !EXTERNAL_ARTIFACTS_FILE.equals(Objects.requireNonNull(file.getFileName()).toString()))
					.sorted().map(file -> artifact(root, file)).toList());
		}
		artifacts.addAll(readExternalArtifacts(jobId));
		return List.copyOf(artifacts);
	}

	synchronized Path artifact(String jobId, String name) throws IOException {
		requireKnown(jobId);
		Path root = jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY);
		Path artifact = root.resolve(name).normalize();
		if (!artifact.startsWith(root) || !Files.isRegularFile(artifact))
			throw new IllegalArgumentException("Unknown artifact: " + name);
		return artifact;
	}

	synchronized Path artifactsDirectory(String jobId) {
		requireKnown(jobId);
		return jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY);
	}

	synchronized void storeArtifact(String jobId, String name, Path source) throws IOException {
		requireKnown(jobId);
		Path root = jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY);
		Path destination = root.resolve(name).normalize();
		if (!destination.startsWith(root))
			throw new IllegalArgumentException("Invalid artifact name: " + name);
		Files.createDirectories(Objects.requireNonNull(destination.getParent()));
		Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
	}

	synchronized void registerArtifact(String jobId, String name, ArtifactReference artifact) throws IOException {
		requireKnown(jobId);
		String expectedKey = "jobs/" + jobId + "/artifacts/" + name;
		if (!expectedKey.equals(artifact.key()))
			throw new IllegalArgumentException("Completed artifact key does not match job history location");
		Path path = artifact(jobId, name);
		if (Files.size(path) != artifact.sizeBytes())
			throw new IOException("Completed artifact size does not match publication metadata");
	}

	synchronized void registerExternalArtifact(String jobId, String name, ArtifactReference artifact)
			throws IOException {
		requireKnown(jobId);
		if (StorageSelection.SERVER_LOCAL.equals(artifact.providerId()))
			throw new IllegalArgumentException("Server-local artifacts must be registered from stored bytes");
		List<Artifact> artifacts = new ArrayList<>(readExternalArtifacts(jobId));
		artifacts.removeIf(existing -> existing.name().equals(name));
		artifacts.add(
				new Artifact(name, artifact.sizeBytes(), artifact.providerId(), artifact.key(), artifact.sha256()));
		writeAtomically(jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY).resolve(EXTERNAL_ARTIFACTS_FILE), artifacts);
	}

	private List<Artifact> readExternalArtifacts(String jobId) throws IOException {
		Path file = jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY).resolve(EXTERNAL_ARTIFACTS_FILE);
		if (!Files.isRegularFile(file))
			return List.of();
		return json.readValue(file.toFile(), json.getTypeFactory().constructCollectionType(List.class, Artifact.class));
	}

	synchronized boolean purge(String jobId) throws IOException {
		if (snapshots.remove(jobId) == null)
			return false;
		Path directory = jobDirectory(jobId);
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
		return true;
	}

	private void load() throws IOException {
		try (var directories = Files.list(jobsRoot)) {
			for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
				Path file = directory.resolve(SNAPSHOT_FILE);
				if (!Files.isRegularFile(file))
					continue;
				try (InputStream input = Files.newInputStream(file)) {
					InMemoryJobMonitor.Snapshot snapshot = json.readValue(input, InMemoryJobMonitor.Snapshot.class);
					if (snapshot.completedAt() == null)
						snapshot = withCompletedAt(snapshot, Files.getLastModifiedTime(file).toInstant().toString());
					if (isTerminal(snapshot.stage())
							&& Objects.requireNonNull(directory.getFileName()).toString().equals(snapshot.jobId()))
						snapshots.put(snapshot.jobId(), snapshot);
				}
			}
		}
	}

	private static InMemoryJobMonitor.Snapshot withCompletedAt(InMemoryJobMonitor.Snapshot snapshot,
			String completedAt) {
		return new InMemoryJobMonitor.Snapshot(snapshot.jobId(), snapshot.plugin(), snapshot.stage(),
				snapshot.progress(), snapshot.elapsed(), snapshot.configuredWorkers(), snapshot.activeWorkers(),
				snapshot.completedWorkUnits(), snapshot.totalWorkUnits(), snapshot.error(), completedAt,
				snapshot.details(), snapshot.workUnits(), snapshot.events());
	}

	private void writeAtomically(Path destination, Object value) throws IOException {
		Path parent = Objects.requireNonNull(destination.getParent(), "Destination must have a parent directory");
		String fileName = Objects.requireNonNull(destination.getFileName(), "Destination must have a file name")
				.toString();
		Path temporary = Files.createTempFile(parent, fileName, ".tmp");
		try {
			json.writeValue(temporary.toFile(), value);
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private Path jobDirectory(String jobId) {
		if (jobId == null || !jobId.matches("[A-Za-z0-9._-]+"))
			throw new IllegalArgumentException("Invalid job ID");
		Path directory = jobsRoot.resolve(jobId).normalize();
		if (!directory.startsWith(jobsRoot))
			throw new IllegalArgumentException("Invalid job ID");
		return directory;
	}

	private void requireKnown(String jobId) {
		if (!snapshots.containsKey(jobId))
			throw new IllegalArgumentException("Unknown completed job: " + jobId);
	}

	private static Artifact artifact(Path root, Path file) {
		try {
			String name = root.relativize(file).toString().replace('\\', '/');
			return new Artifact(name, Files.size(file), "server-local",
					"jobs/" + Objects.requireNonNull(root.getParent().getFileName()) + "/artifacts/" + name,
					sha256(file));
		} catch (IOException failure) {
			throw new java.io.UncheckedIOException(failure);
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0;)
					if (read > 0)
						digest.update(buffer, 0, read);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static boolean isTerminal(String stage) {
		return "SUCCEEDED".equals(stage) || "FAILED".equals(stage) || "CANCELLED".equals(stage);
	}
}
