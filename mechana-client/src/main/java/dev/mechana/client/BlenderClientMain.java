package dev.mechana.client;

import dev.mechana.protocol.Messages.BlenderJobSubmitRequest;
import java.io.IOException;
import java.net.URI;

/** Submits a distributed Blender frame-rendering job. */
public final class BlenderClientMain {
	private BlenderClientMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		if (args.length < 2)
			throw new IllegalArgumentException(
					"Usage: BlenderClientMain <server> <packed.blend> [first] [last] [tasks] [width] [height] [samples] [fps]");
		MechanaClient client = new MechanaClient(URI.create(args[0]));
		var request = new BlenderJobSubmitRequest(args[1], integer(args, 2, 1), integer(args, 3, 240),
				integer(args, 4, 12), integer(args, 5, 960), integer(args, 6, 540), integer(args, 7, 16),
				integer(args, 8, 24));
		String jobId = client.submitBlender(request);
		System.out.printf("Submitted Blender job %s%n", jobId);
		System.out.printf("Loopback job dashboard: %s%n", client.dashboard(jobId));
	}

	private static int integer(String[] args, int index, int fallback) {
		return args.length > index ? Integer.parseInt(args[index]) : fallback;
	}
}
