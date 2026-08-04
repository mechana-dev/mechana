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
