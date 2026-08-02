package dev.mechana.client;

import dev.mechana.protocol.Messages.FractalJobSubmitRequest;
import java.io.IOException;
import java.net.URI;

/** Submits a distributed fractal collection job. */
public final class FractalClientMain {
	private FractalClientMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		URI server = URI.create(args.length > 0 ? args[0] : "http://localhost:8787");
		int images = args.length > 1 ? Integer.parseInt(args[1]) : 24;
		int tasks = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		int width = args.length > 3 ? Integer.parseInt(args[3]) : 1920;
		int height = args.length > 4 ? Integer.parseInt(args[4]) : 1080;
		int iterations = args.length > 5 ? Integer.parseInt(args[5]) : 4000;
		long seed = args.length > 6 ? Long.parseLong(args[6]) : 1;
		MechanaClient client = new MechanaClient(server);
		String jobId = client
				.submitFractals(new FractalJobSubmitRequest(images, tasks, width, height, iterations, seed));
		System.out.printf("Submitted fractal job %s%n", jobId);
		System.out.printf("Loopback job dashboard: %s%n", client.dashboard(jobId));
	}
}
