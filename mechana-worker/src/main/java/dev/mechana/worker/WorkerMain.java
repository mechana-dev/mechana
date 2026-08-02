package dev.mechana.worker;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Starts a worker process that remains connected and waits for work. */
public final class WorkerMain {

	private WorkerMain() {
	}

	public static void main(String[] args) {
		URI server = URI.create(args.length > 0 ? args[0] : "http://localhost:8787");
		Set<String> plugins = args.length > 1
				? Arrays.stream(args[1].split(",")).collect(Collectors.toSet())
				: Set.of("sleep");
		String workerId = args.length > 2 ? args[2] : UUID.randomUUID().toString();

		System.out.printf("Worker %s connecting to %s with capabilities %s%n", workerId, server, plugins);
		WorkerAgent worker = new WorkerAgent(server, workerId, plugins);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				worker.disconnect();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				System.err.printf("Worker %s could not notify server of shutdown: interrupted%n", workerId);
			} catch (IOException failure) {
				System.err.printf("Worker %s could not notify server of shutdown: %s%n", workerId,
						failure.getMessage());
			}
		}, "mechana-worker-shutdown"));
		worker.runForever();
	}
}
