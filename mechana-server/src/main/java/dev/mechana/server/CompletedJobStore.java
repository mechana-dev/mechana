package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.coordinator.InMemoryJobMonitor;
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
	record Artifact(String name, long size) {
	}

	private static final String SNAPSHOT_FILE = "snapshot.json";
	private static final String ARTIFACTS_DIRECTORY = "artifacts";
	private final Path jobsRoot;
	private final ObjectMapper json;
	private final Map<String, InMemoryJobMonitor.Snapshot> snapshots = new LinkedHashMap<>();

	CompletedJobStore(Path dataDirectory, ObjectMapper json) throws IOException {
		this.jobsRoot = dataDirectory.toAbsolutePath().normalize().resolve("jobs");
		this.json = json;
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
		writeAtomically(artifacts.resolve("job-summary.json"), snapshot);
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
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).sorted().map(file -> artifact(root, file)).toList();
		}
	}

	synchronized Path artifact(String jobId, String name) throws IOException {
		requireKnown(jobId);
		Path root = jobDirectory(jobId).resolve(ARTIFACTS_DIRECTORY);
		Path artifact = root.resolve(name).normalize();
		if (!artifact.startsWith(root) || !Files.isRegularFile(artifact))
			throw new IllegalArgumentException("Unknown artifact: " + name);
		return artifact;
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
					if (isTerminal(snapshot.stage())
							&& Objects.requireNonNull(directory.getFileName()).toString().equals(snapshot.jobId()))
						snapshots.put(snapshot.jobId(), snapshot);
				}
			}
		}
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
			return new Artifact(root.relativize(file).toString().replace('\\', '/'), Files.size(file));
		} catch (IOException failure) {
			throw new java.io.UncheckedIOException(failure);
		}
	}

	private static boolean isTerminal(String stage) {
		return "SUCCEEDED".equals(stage) || "FAILED".equals(stage) || "CANCELLED".equals(stage);
	}
}
