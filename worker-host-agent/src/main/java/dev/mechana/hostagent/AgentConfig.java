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
