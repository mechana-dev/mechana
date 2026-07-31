package dev.mechana.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Submits one parallel sleep job and waits for its terminal result. */
public final class ClientMain {

	private ClientMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		URI server = URI.create(args.length > 0 ? args[0] : "http://localhost:8080");
		int tasks = args.length > 1 ? Integer.parseInt(args[1]) : 4;
		long durationMillis = args.length > 2 ? Long.parseLong(args[2]) : 5_000;

		long startedAt = System.nanoTime();
		MechanaClient client = new MechanaClient(server);
		String jobId = client.submit(tasks, durationMillis);
		System.out.printf("Submitted job %s with %d task(s)%n", jobId, tasks);
		var result = client.waitForCompletion(jobId, 500);
		System.out.printf("Job %s finished: %s (%d%%)%n", result.jobId(), result.state(), result.progress());
		result.tasks().forEach(
				task -> System.out.printf("  %s: %s, attempts=%d%n", task.taskId(), task.state(), task.attempt()));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		System.out.printf("Total wall-clock time: %.3f seconds%n", elapsed.toNanos() / 1_000_000_000.0);
	}
}
