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

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public record AgentConfig(String bindAddress, int port, String token, URI coordinator, Path javaExecutable,
		Path workerJar, Path workingDirectory, int maxWorkers, String capabilities, Duration stopTimeout,
		String machineName, boolean allowUnauthenticated) {
	public AgentConfig {
		Objects.requireNonNull(bindAddress);
		Objects.requireNonNull(token);
		Objects.requireNonNull(coordinator);
		Objects.requireNonNull(javaExecutable);
		Objects.requireNonNull(workerJar);
		Objects.requireNonNull(workingDirectory);
		Objects.requireNonNull(capabilities);
		Objects.requireNonNull(stopTimeout);
		Objects.requireNonNull(machineName);
		if (port < 0 || port > 65535 || maxWorkers < 1 || stopTimeout.isNegative() || stopTimeout.isZero())
			throw new IllegalArgumentException("Invalid port, max-workers, or stop-timeout");
		if (machineName.isBlank())
			throw new IllegalArgumentException("Machine name must not be blank");
		if (!isLoopback(bindAddress) && token.isBlank() && !allowUnauthenticated)
			throw new IllegalArgumentException(
					"A token is required when binding beyond loopback unless allow-unauthenticated=true");
	}

	public static AgentConfig from(Properties p) {
		return new AgentConfig(p.getProperty("bind-address", "127.0.0.1"),
				Integer.parseInt(p.getProperty("port", "8790")), p.getProperty("token", ""),
				URI.create(p.getProperty("coordinator", "http://127.0.0.1:8787")),
				Path.of(p.getProperty("java", defaultJava())),
				Path.of(p.getProperty("worker-jar", "mechana-worker/target/mechana-worker.jar")),
				Path.of(p.getProperty("working-directory", ".")), Integer.parseInt(p.getProperty("max-workers", "16")),
				p.getProperty("capabilities", "sleep"),
				Duration.ofMillis(Long.parseLong(p.getProperty("stop-timeout-ms", "10000"))),
				normalizeMachineName(p.getProperty("machine-name", localMachineName())),
				Boolean.parseBoolean(p.getProperty("allow-unauthenticated", "false")));
	}

	private static String localMachineName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception failure) {
			return "worker-host";
		}
	}

	private static String normalizeMachineName(String value) {
		String shortName = value.strip().split("\\.", 2)[0];
		String normalized = shortName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$",
				"");
		return normalized.isBlank() ? "worker-host" : normalized;
	}

	private static String defaultJava() {
		return Path.of(System.getProperty("java.home"), "bin",
				System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
				.toString();
	}

	private static boolean isLoopback(String address) {
		try {
			return InetAddress.getByName(address).isLoopbackAddress();
		} catch (Exception failure) {
			return false;
		}
	}
}
