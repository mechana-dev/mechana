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
