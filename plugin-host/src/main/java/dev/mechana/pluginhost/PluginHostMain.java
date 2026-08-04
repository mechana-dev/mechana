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
package dev.mechana.pluginhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mechana.api.PluginDescriptor;
import dev.mechana.api.PluginExecutionException;
import dev.mechana.api.TaskContext;
import dev.mechana.api.TaskPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Fault-isolated TaskPlugin host using one-request NDJSON over stdin/stdout.
 */
public final class PluginHostMain {
	private PluginHostMain() {
	}
	public static void main(String[] args) {
		ObjectMapper json = new ObjectMapper();
		PrintStream protocol = System.out;
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String frame = input.readLine();
			if (frame == null || frame.isBlank())
				throw new IllegalArgumentException("Host request frame is required");
			HostRequest request = json.readValue(frame, HostRequest.class);
			execute(request, event -> write(json, protocol, event));
			write(json, protocol, new HostEvent("completed", 100, null, Map.of()));
		} catch (Throwable failure) {
			write(json, protocol, new HostEvent("failed", null, safeMessage(failure), Map.of()));
			System.exit(1);
		}
	}
	static void execute(HostRequest request, EventSink events)
			throws IOException, ReflectiveOperationException, PluginExecutionException {
		Path output = Path.of(request.outputDirectory()).toAbsolutePath().normalize();
		Files.createDirectories(output);
		try (URLClassLoader loader = new URLClassLoader(
				new java.net.URL[]{Path.of(request.pluginJar()).toUri().toURL()}, TaskPlugin.class.getClassLoader())) {
			TaskPlugin plugin = Class.forName(request.entrypoint(), true, loader).asSubclass(TaskPlugin.class)
					.getConstructor().newInstance();
			PluginDescriptor descriptor = plugin.descriptor();
			if (!descriptor.id().equals(request.expectedId())
					|| !descriptor.version().equals(request.expectedVersion()))
				throw new IllegalArgumentException("Plugin identity does not match host request");
			plugin.execute(new HostContext(request, output, events));
		}
	}
	private static void write(ObjectMapper json, PrintStream protocol, HostEvent event) {
		try {
			protocol.println(json.writeValueAsString(event));
			protocol.flush();
		} catch (IOException ignored) {
		}
	}
	private static String safeMessage(Throwable failure) {
		return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
	}
	@FunctionalInterface
	interface EventSink {
		void accept(HostEvent event);
	}
	private record HostContext(HostRequest request, Path output, EventSink events) implements TaskContext {
		@Override
		public long durationMillis() {
			return request.durationMillis();
		}
		@Override
		public Map<String, String> parameters() {
			return Map.copyOf(request.parameters());
		}
		@Override
		public void publishArtifact(String name, Path file) {
			Path source = file.toAbsolutePath().normalize();
			Path target = output.resolve(name).normalize();
			if (!target.startsWith(output))
				throw new IllegalArgumentException("Artifact path escapes output directory");
			try {
				Files.copy(source, target);
			} catch (IOException failure) {
				throw new IllegalStateException("Could not stage artifact", failure);
			}
			events.accept(new HostEvent("artifact", null, null, Map.of("name", name, "path", target.toString())));
		}
		@Override
		public void reportProgress(int percent) {
			if (percent < 0 || percent > 100)
				throw new IllegalArgumentException("Progress must be 0..100");
			events.accept(new HostEvent("progress", percent, null, Map.of()));
		}
		@Override
		public boolean isCancellationRequested() {
			return false;
		}
	}
}
