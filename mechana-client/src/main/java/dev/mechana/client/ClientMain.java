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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Submits one parallel sleep job and waits for its terminal result. */
public final class ClientMain {

	private ClientMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		URI server = URI.create(args.length > 0 ? args[0] : "http://localhost:8787");
		int tasks = args.length > 1 ? Integer.parseInt(args[1]) : 4;
		String durationArgument = args.length > 2 ? args[2] : "5000";
		List<Long> durations = Arrays.stream(durationArgument.split(",")).map(Long::parseLong).toList();
		if (durations.size() == 1)
			durations = java.util.Collections.nCopies(tasks, durations.getFirst());
		else if (durations.size() != tasks)
			throw new IllegalArgumentException("Provide one duration or one comma-separated duration per task");

		long startedAt = System.nanoTime();
		MechanaClient client = new MechanaClient(server);
		String jobId = client.submit(durations);
		System.out.printf("Submitted job %s with %d task(s)%n", jobId, tasks);
		System.out.printf("Loopback job dashboard: %s%n", client.dashboard(jobId));
		var result = client.waitForCompletion(jobId, 500);
		System.out.printf("Job %s finished: %s (%d%%)%n", result.jobId(), result.state(), result.progress());
		result.tasks().forEach(
				task -> System.out.printf("  %s: %s, attempts=%d%n", task.taskId(), task.state(), task.attempt()));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		System.out.printf("Total wall-clock time: %.3f seconds%n", elapsed.toNanos() / 1_000_000_000.0);
	}
}
