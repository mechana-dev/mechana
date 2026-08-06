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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorkerManager {
	private static final Set<String> IMPLEMENTED_SANDBOX_PLUGINS = Set.of("sleep", "video-ffmpeg", "fractal-render",
			"ocr-tesseract", "blender-render");
	record WorkerStatus(String id, long pid, Instant startedAt, boolean alive) {
	}
	record LaunchRequest(int count, WorkerLaunchMode mode, String capabilities) {
	}
	record Status(int requestedCount, int runningCount, String state, List<WorkerStatus> workers, String diagnostic,
			WorkerLaunchMode launchMode, String capabilities, String sandboxRoot) {
	}
	private record Entry(String id, ManagedProcess process, Instant startedAt) {
	}

	private final AgentConfig config;
	private final ProcessLauncher launcher;
	private final Map<String, Entry> workers = new LinkedHashMap<>();
	private int requestedCount;
	private String diagnostic = "";
	private WorkerLaunchMode launchMode;
	private String launchCapabilities = "";

	WorkerManager(AgentConfig config, ProcessLauncher launcher) {
		this.config = config;
		this.launcher = launcher;
	}

	synchronized Status start(int count) throws IOException {
		return start(new LaunchRequest(count, WorkerLaunchMode.LEGACY, config.capabilities()));
	}

	synchronized Status start(LaunchRequest request) throws IOException {
		pruneDead();
		int count = request.count();
		if (count < 0 || count > config.maxWorkers())
			throw new IllegalArgumentException("Worker count must be between 0 and " + config.maxWorkers());
		WorkerLaunchMode mode = request.mode() == null ? WorkerLaunchMode.LEGACY : request.mode();
		String capabilities = validatedCapabilities(mode, request.capabilities());
		if (!workers.isEmpty() && (mode != launchMode || !capabilities.equals(launchCapabilities)))
			throw new IllegalArgumentException("Stop all workers before changing launch mode or plugins");
		requestedCount = count;
		launchMode = mode;
		launchCapabilities = capabilities;
		Path logs = config.workingDirectory().resolve("worker-logs");
		Files.createDirectories(logs);
		try {
			for (int i = workers.size(); i < count; i++) {
				String id = config.machineName() + "-" + UUID.randomUUID();
				List<String> command = command(mode, capabilities, id);
				ManagedProcess process = launcher.launch(command, config.workingDirectory(), logs.resolve(id + ".log"));
				workers.put(id, new Entry(id, process, Instant.now()));
			}
			diagnostic = "";
		} catch (IOException failure) {
			diagnostic = failure.getMessage();
			throw failure;
		}
		return status();
	}

	synchronized Status stopAll() throws InterruptedException {
		requestedCount = 0;
		List<Entry> snapshot = new ArrayList<>(workers.values());
		snapshot.stream().filter(e -> e.process().isAlive()).forEach(e -> e.process().destroy());
		for (Entry entry : snapshot)
			if (entry.process().isAlive() && !entry.process().waitFor(config.stopTimeout()))
				entry.process().destroyForcibly();
		workers.clear();
		diagnostic = "";
		launchMode = null;
		launchCapabilities = "";
		return status();
	}

	synchronized Status status() {
		pruneDead();
		List<WorkerStatus> items = workers.values().stream()
				.map(e -> new WorkerStatus(e.id(), e.process().pid(), e.startedAt(), e.process().isAlive())).toList();
		String state = workers.isEmpty() ? "STOPPED" : workers.size() == requestedCount ? "RUNNING" : "ERROR";
		return new Status(requestedCount, workers.size(), state, items, diagnostic, launchMode, launchCapabilities,
				launchMode == WorkerLaunchMode.SANDBOXED ? config.sandboxRoot().toString() : "");
	}

	private List<String> command(WorkerLaunchMode mode, String capabilities, String id) {
		List<String> command = new ArrayList<>();
		command.add(config.javaExecutable().toString());
		if (mode == WorkerLaunchMode.SANDBOXED) {
			command.add("-Dmechana.sandbox.root=" + config.sandboxRoot().toAbsolutePath().normalize());
			command.add("-Dmechana.execution.mode=sandboxed");
			copyRuntimeProperty(command, "ffmpeg");
			copyRuntimeProperty(command, "ffprobe");
			copyRuntimeProperty(command, "tesseract");
			copyRuntimeProperty(command, "blender");
			copyPathProperty(command, "mechana.windows.sandbox.launcher");
		}
		command.add("-jar");
		command.add(config.workerJar().toAbsolutePath().normalize().toString());
		command.add(config.coordinator().toString());
		command.add(capabilities);
		command.add(id);
		return List.copyOf(command);
	}

	private static void copyRuntimeProperty(List<String> command, String name) {
		copyPathProperty(command, "mechana.runtime." + name);
	}

	private static void copyPathProperty(List<String> command, String property) {
		String value = System.getProperty(property, "").strip();
		if (!value.isEmpty())
			command.add("-D" + property + "=" + Path.of(value).toAbsolutePath().normalize());
	}

	private String validatedCapabilities(WorkerLaunchMode mode, String requested) {
		String configured = mode == WorkerLaunchMode.SANDBOXED ? config.sandboxedCapabilities() : config.capabilities();
		Set<String> allowed = capabilitySet(configured);
		if (mode == WorkerLaunchMode.SANDBOXED)
			allowed.retainAll(IMPLEMENTED_SANDBOX_PLUGINS);
		Set<String> selected = capabilitySet(requested == null || requested.isBlank() ? configured : requested);
		if (selected.isEmpty() || !allowed.containsAll(selected))
			throw new IllegalArgumentException("Requested plugins are not allowed for " + mode + ": " + selected);
		if (mode == WorkerLaunchMode.SANDBOXED) {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (!os.contains("mac") && !os.contains("linux") && !os.contains("windows"))
				throw new IllegalArgumentException("Sandboxed workers currently require macOS, Linux, or Windows");
		}
		return String.join(",", selected);
	}

	private static Set<String> capabilitySet(String value) {
		Set<String> result = new LinkedHashSet<>();
		for (String item : value.split(",")) {
			String capability = item.strip();
			if (!capability.isEmpty()) {
				if (!capability.matches("[a-z0-9][a-z0-9-]*"))
					throw new IllegalArgumentException("Invalid plugin capability: " + capability);
				result.add(capability);
			}
		}
		return result;
	}

	private void pruneDead() {
		workers.values().removeIf(e -> !e.process().isAlive());
	}
}
