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

package dev.mechana.hostagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class HostAgentMain {
	private HostAgentMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		Properties properties = new Properties();
		if (args.length > 0) {
			try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
				properties.load(input);
			}
		}
		AgentConfig config = AgentConfig.from(properties);
		validatePaths(config);
		WorkerManager manager = new WorkerManager(config, ProcessLauncher.system());
		HostAgentServer server = new HostAgentServer(config, manager);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				manager.stopAll();
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
			}
			server.close();
		}, "mechana-host-agent-shutdown"));
		server.start();
		System.out.printf("Mechana Worker Host Agent listening on %s:%d%n", config.bindAddress(), server.port());
		Thread.currentThread().join();
	}

	private static void validatePaths(AgentConfig config) throws IOException {
		if (!Files.isRegularFile(config.javaExecutable()))
			throw new IOException("Java executable not found: " + config.javaExecutable());
		if (!Files.isRegularFile(config.workerJar()))
			throw new IOException("Worker JAR not found: " + config.workerJar());
		Files.createDirectories(config.workingDirectory());
	}
}
