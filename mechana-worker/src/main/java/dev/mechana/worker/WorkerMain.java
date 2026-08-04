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
