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

package dev.mechana.plugins.video;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ExternalProcessRunner {
	public record Result(int exitCode, String stdout, String stderr) {
	}

	public Result run(List<String> command, Duration timeout, CancellationToken cancellation,
			Consumer<String> stdoutLineConsumer) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).start();
		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread out = Thread.ofVirtual()
				.start(() -> drain(process.inputReader(StandardCharsets.UTF_8), stdout, stdoutLineConsumer));
		Thread err = Thread.ofVirtual()
				.start(() -> drain(process.errorReader(StandardCharsets.UTF_8), stderr, ignored -> {
				}));
		Instant deadline = Instant.now().plus(timeout);
		try {
			while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
				if (cancellation.isCancelled())
					throw new ProcessCancelledException("Process cancelled");
				if (Instant.now().isAfter(deadline))
					throw new ProcessTimeoutException("Process timed out after " + timeout);
			}
		} catch (RuntimeException | InterruptedException failure) {
			terminate(process);
			throw failure;
		} finally {
			out.join();
			err.join();
		}
		return new Result(process.exitValue(), stdout.toString(), stderr.toString());
	}

	private static void drain(BufferedReader reader, StringBuilder target, Consumer<String> consumer) {
		try (reader) {
			String line;
			while ((line = reader.readLine()) != null) {
				target.append(line).append('\n');
				consumer.accept(line);
			}
		} catch (IOException ignored) {
		}
	}

	private static void terminate(Process process) {
		destroyDescendants(process, false);
		process.destroy();
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				destroyDescendants(process, true);
				process.destroyForcibly();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private static void destroyDescendants(Process process, boolean forcibly) {
		try {
			process.descendants().forEach(child -> {
				if (forcibly)
					child.destroyForcibly();
				else
					child.destroy();
			});
		} catch (RuntimeException unavailable) {
			// Some restricted hosts deny process-tree inspection; the direct child is still
			// terminated below.
		}
	}

	public static final class ProcessTimeoutException extends RuntimeException {
		public ProcessTimeoutException(String m) {
			super(m);
		}
	}
	public static final class ProcessCancelledException extends RuntimeException {
		public ProcessCancelledException(String m) {
			super(m);
		}
	}
}
