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
				.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
		builder.environment().clear();
		builder.environment().putAll(request.environment());
		builder.environment().put("HOME", request.workspace().work().toString());
		builder.environment().put("TMPDIR", request.workspace().work().toString());
		Instant started = Instant.now();
		Process process = builder.start();
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
		return new SandboxResult(exitCode, timedOut, cancellation.get(), Duration.between(started, Instant.now()),
				stdout, stderr, capabilities);
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
