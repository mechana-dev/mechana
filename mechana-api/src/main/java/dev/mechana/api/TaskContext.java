package dev.mechana.api;

/** Services exposed by a worker to a running plugin. */
public interface TaskContext {

	long durationMillis();

	void reportProgress(int percent);

	boolean isCancellationRequested();
}
