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
package dev.mechana.runtime.plugin;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate-process lifecycle isolation shared by managed backends. */
public class ProcessSandbox implements PluginSandbox {
	@Override
	public SandboxCapabilities capabilities(SandboxPolicy policy) {
		return new SandboxCapabilities("managed-process",
				Map.of(SandboxControl.TIMEOUT, true, SandboxControl.PROCESS_TREE_TERMINATION, false),
				"Process separation is not an OS security sandbox");
	}

	@Override
	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		return executeCommand(request, cancellation, request.command(), capabilities(request.policy()));
	}

	protected final SandboxResult executeCommand(SandboxRequest request, AtomicBoolean cancellation,
			List<String> command, SandboxCapabilities capabilities) throws IOException, InterruptedException {
		Path stdout = request.workspace().logs().resolve("stdout.log");
		Path stderr = request.workspace().logs().resolve("stderr.log");
		ProcessBuilder builder = new ProcessBuilder(command).directory(request.workspace().work().toFile())
				.redirectError(stderr.toFile());
		if (request.standardInput() != null)
			builder.redirectInput(request.standardInput().toFile());
		builder.environment().clear();
		builder.environment().putAll(request.environment());
		builder.environment().put("HOME", request.workspace().work().toString());
		builder.environment().put("TMPDIR", request.workspace().work().toString());
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot == null || systemRoot.isBlank())
				systemRoot = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
			builder.environment().putIfAbsent("SystemRoot", systemRoot);
			builder.environment().putIfAbsent("WINDIR", systemRoot);
			copyHostEnvironment(builder.environment(), "ComSpec");
			copyHostEnvironment(builder.environment(), "PATH");
			copyHostEnvironment(builder.environment(), "ProgramData");
			copyHostEnvironment(builder.environment(), "LOCALAPPDATA");
			for (String name : List.of("ALLUSERSPROFILE", "CommonProgramFiles", "CommonProgramFiles(x86)",
					"CommonProgramW6432", "DriverData", "NUMBER_OF_PROCESSORS", "OS", "PATHEXT",
					"PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", "PROCESSOR_REVISION",
					"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "PUBLIC", "SystemDrive", "COMPUTERNAME",
					"USERDOMAIN", "USERNAME", "USER"))
				copyHostEnvironment(builder.environment(), name);
			System.getenv().entrySet().stream().filter(entry -> entry.getKey().endsWith("_POSIX_FD_STATE"))
					.forEach(entry -> builder.environment().putIfAbsent(entry.getKey(), entry.getValue()));
			builder.environment().put("USERPROFILE", request.workspace().work().toString());
			String work = request.workspace().work().toString();
			if (work.length() >= 3 && work.charAt(1) == ':') {
				builder.environment().put("HOMEDRIVE", work.substring(0, 2));
				builder.environment().put("HOMEPATH", work.substring(2));
			}
			builder.environment().put("APPDATA", request.workspace().work().resolve("AppData").toString());
			builder.environment().put("TEMP", request.workspace().work().toString());
			builder.environment().put("TMP", request.workspace().work().toString());
		}
		Instant started = Instant.now();
		Process process = builder.start();
		Thread stdoutReader = Thread.ofVirtual().name("mechana-sandbox-stdout").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					BufferedWriter log = Files.newBufferedWriter(stdout, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					log.write(line);
					log.newLine();
					log.flush();
					if (request.stdoutLineConsumer() != null)
						request.stdoutLineConsumer().accept(line);
				}
			} catch (IOException ignored) {
				// Process termination may close the stream while it is being consumed.
			}
		});
		long deadline = System.nanoTime() + request.policy().timeout().toNanos();
		boolean timedOut = false;
		while (process.isAlive()) {
			if (cancellation.get()) {
				terminateTree(process);
				break;
			}
			if (System.nanoTime() >= deadline) {
				timedOut = true;
				terminateTree(process);
				break;
			}
			process.waitFor(25, TimeUnit.MILLISECONDS);
		}
		int exitCode = process.isAlive() ? -1 : process.exitValue();
		stdoutReader.join();
		return new SandboxResult(exitCode, timedOut, cancellation.get(), Duration.between(started, Instant.now()),
				stdout, stderr, capabilities);
	}

	private static void copyHostEnvironment(Map<String, String> environment, String name) {
		String value = System.getenv(name);
		if (value != null && !value.isBlank())
			environment.putIfAbsent(name, value);
	}

	private static void terminateTree(Process process) throws InterruptedException {
		destroyDescendants(process, false);
		process.destroy();
		if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
			destroyDescendants(process, true);
			process.destroyForcibly();
			process.waitFor(2, TimeUnit.SECONDS);
		}
	}

	private static void destroyDescendants(Process process, boolean forcibly) {
		try {
			process.toHandle().descendants().forEach(handle -> {
				if (forcibly)
					handle.destroyForcibly();
				else
					handle.destroy();
			});
		} catch (RuntimeException unavailable) {
			// Some constrained hosts deny process-tree enumeration. The direct child is
			// still terminated.
		}
	}
}
