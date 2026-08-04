package dev.mechana.hostagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkerManager {
	record WorkerStatus(String id, long pid, Instant startedAt, boolean alive) {
	}
	record Status(int requestedCount, int runningCount, String state, List<WorkerStatus> workers, String diagnostic) {
	}
	private record Entry(String id, ManagedProcess process, Instant startedAt) {
	}

	private final AgentConfig config;
	private final ProcessLauncher launcher;
	private final Map<String, Entry> workers = new LinkedHashMap<>();
	private int requestedCount;
	private String diagnostic = "";

	WorkerManager(AgentConfig config, ProcessLauncher launcher) {
		this.config = config;
		this.launcher = launcher;
	}

	synchronized Status start(int count) throws IOException {
		pruneDead();
		if (count < 0 || count > config.maxWorkers())
			throw new IllegalArgumentException("Worker count must be between 0 and " + config.maxWorkers());
		requestedCount = count;
		Path logs = config.workingDirectory().resolve("worker-logs");
		Files.createDirectories(logs);
		try {
			for (int i = workers.size(); i < count; i++) {
				String id = config.machineName() + "-" + UUID.randomUUID();
				List<String> command = List.of(config.javaExecutable().toString(), "-jar",
						config.workerJar().toAbsolutePath().normalize().toString(), config.coordinator().toString(),
						config.capabilities(), id);
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
		return status();
	}

	synchronized Status status() {
		pruneDead();
		List<WorkerStatus> items = workers.values().stream()
				.map(e -> new WorkerStatus(e.id(), e.process().pid(), e.startedAt(), e.process().isAlive())).toList();
		String state = workers.isEmpty() ? "STOPPED" : workers.size() == requestedCount ? "RUNNING" : "ERROR";
		return new Status(requestedCount, workers.size(), state, items, diagnostic);
	}

	private void pruneDead() {
		workers.values().removeIf(e -> !e.process().isAlive());
	}
}
