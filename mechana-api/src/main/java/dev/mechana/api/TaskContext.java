package dev.mechana.api;

import java.nio.file.Path;
import java.util.Map;

/** Services exposed by a worker to a running plugin. */
public interface TaskContext {

	long durationMillis();

	default Map<String, String> parameters() {
		return Map.of();
	}

	default void publishArtifact(String name, Path file) {
		throw new UnsupportedOperationException("Artifact publication is unavailable");
	}

	void reportProgress(int percent);

	boolean isCancellationRequested();
}
