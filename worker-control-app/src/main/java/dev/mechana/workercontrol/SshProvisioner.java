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
package dev.mechana.workercontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Installs and controls the host agent over an existing OpenSSH connection. */
final class SshProvisioner {
	enum RemoteOs {
		MACOS, LINUX
	}
	record Request(String host, String sshUser, int sshPort, Path identityFile, boolean acceptNewHostKey, Path agentJar,
			Path workerJar, String remoteDirectory, String coordinator, int agentPort, String token,
			String legacyCapabilities, String sandboxedCapabilities, String sandboxRoot) {
	}
	record Result(RemoteOs os, String remoteDirectory, String message) {
	}

	@FunctionalInterface
	interface CommandRunner {
		String run(List<String> command, Duration timeout) throws IOException, InterruptedException;
	}

	private static final String LABEL = "dev.mechana.worker-host-agent";
	private final CommandRunner commands;

	SshProvisioner() {
		this(SshProvisioner::runCommand);
	}
	SshProvisioner(CommandRunner commands) {
		this.commands = commands;
	}

	Result deploy(Request request) throws IOException, InterruptedException {
		validate(request);
		String target = target(request);
		RemoteOs os = detect(request, target);
		String home = ssh(request, target, "pwd").strip();
		String remote = resolveRemoteDirectory(home, request.remoteDirectory());
		String java = ssh(request, target, "command -v java").strip();
		if (java.isBlank())
			throw new IOException("Java is not available in the remote SSH PATH");
		Map<String, String> runtimes = discoverRuntimes(request, target, os);
		ssh(request, target,
				"mkdir -p " + quote(remote) + " " + quote(remote + "/data") + " " + quote(request.sandboxRoot()));

		Path temporary = Files.createTempDirectory("mechana-agent-deploy-");
		try {
			Path config = temporary.resolve("worker-host-agent.properties");
			writeConfig(config, request, java, remote);
			copy(request, target, request.agentJar(), remote + "/mechana-worker-host-agent.jar");
			copy(request, target, request.workerJar(), remote + "/mechana-worker.jar");
			copy(request, target, config, remote + "/worker-host-agent.properties");
			if (os == RemoteOs.MACOS)
				installMacOs(request, target, temporary, java, remote, home, runtimes);
			else
				installLinux(request, target, temporary, java, remote, home, runtimes);
		} finally {
			deleteTree(temporary);
		}
		return new Result(os, remote, "Agent installed and started");
	}

	Result stop(Request request) throws IOException, InterruptedException {
		validateConnection(request);
		String target = target(request);
		RemoteOs os = detect(request, target);
		if (os == RemoteOs.MACOS)
			ssh(request, target, "launchctl bootout gui/$(id -u)/" + LABEL + " 2>/dev/null || true");
		else
			ssh(request, target, "systemctl --user disable --now " + LABEL + ".service");
		return new Result(os, request.remoteDirectory(), "Agent and its managed workers stopped");
	}

	Result restart(Request request) throws IOException, InterruptedException {
		validateConnection(request);
		String target = target(request);
		RemoteOs os = detect(request, target);
		String home = ssh(request, target, "pwd").strip();
		if (os == RemoteOs.MACOS) {
			String plist = home + "/Library/LaunchAgents/" + LABEL + ".plist";
			String service = "gui/$(id -u)/" + LABEL;
			ssh(request, target,
					"test -f " + quote(plist) + "; launchctl kickstart -k " + service + " 2>/dev/null || { "
							+ "launchctl bootstrap gui/$(id -u) " + quote(plist) + " || launchctl kickstart -k "
							+ service + "; }");
		} else {
			ssh(request, target, "systemctl --user restart " + LABEL + ".service");
		}
		return new Result(os, request.remoteDirectory(), "Agent restarted");
	}

	private RemoteOs detect(Request request, String target) throws IOException, InterruptedException {
		String name = ssh(request, target, "uname -s").strip().toLowerCase(Locale.ROOT);
		return switch (name) {
			case "darwin" -> RemoteOs.MACOS;
			case "linux" -> RemoteOs.LINUX;
			default -> throw new IOException("Unsupported remote SSH platform: " + name
					+ ". This installer currently supports macOS and Linux only.");
		};
	}

	private void installMacOs(Request request, String target, Path temporary, String java, String remote, String home,
			Map<String, String> runtimes) throws IOException, InterruptedException {
		Path plist = temporary.resolve(LABEL + ".plist");
		Files.writeString(plist, macOsPlist(java, remote, runtimes), StandardCharsets.UTF_8);
		String launchAgents = home + "/Library/LaunchAgents";
		ssh(request, target, "mkdir -p " + quote(launchAgents));
		copy(request, target, plist, launchAgents + "/" + LABEL + ".plist");
		ssh(request, target,
				"launchctl bootout gui/$(id -u)/" + LABEL + " 2>/dev/null || true; "
						+ macOsPortReleaseCommand(request.agentPort()) + "; launchctl bootstrap gui/$(id -u) "
						+ quote(launchAgents + "/" + LABEL + ".plist"));
	}

	private void installLinux(Request request, String target, Path temporary, String java, String remote, String home,
			Map<String, String> runtimes) throws IOException, InterruptedException {
		Path service = temporary.resolve(LABEL + ".service");
		Files.writeString(service, linuxService(java, remote, runtimes), StandardCharsets.UTF_8);
		String serviceDirectory = home + "/.config/systemd/user";
		ssh(request, target, "mkdir -p " + quote(serviceDirectory));
		copy(request, target, service, serviceDirectory + "/" + LABEL + ".service");
		ssh(request, target, "systemctl --user daemon-reload; systemctl --user enable --now " + LABEL + ".service");
	}

	private String ssh(Request request, String target, String remoteCommand) throws IOException, InterruptedException {
		List<String> command = sshBase(request);
		command.add(target);
		command.add(remoteCommand);
		return commands.run(command, Duration.ofSeconds(60));
	}

	private void copy(Request request, String target, Path local, String remote)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("scp");
		command.addAll(options(request, true));
		command.add(local.toAbsolutePath().normalize().toString());
		command.add(target + ":" + remote);
		commands.run(command, Duration.ofMinutes(2));
	}

	private static List<String> sshBase(Request request) {
		List<String> command = new ArrayList<>();
		command.add("ssh");
		command.addAll(options(request, false));
		return command;
	}

	private static List<String> options(Request request, boolean scp) {
		List<String> options = new ArrayList<>();
		options.add(scp ? "-P" : "-p");
		options.add(Integer.toString(request.sshPort()));
		options.add("-o");
		options.add("BatchMode=yes");
		options.add("-o");
		options.add("ConnectTimeout=10");
		options.add("-o");
		options.add("StrictHostKeyChecking=" + (request.acceptNewHostKey() ? "accept-new" : "yes"));
		if (request.identityFile() != null) {
			options.add("-i");
			options.add(request.identityFile().toAbsolutePath().normalize().toString());
		}
		return options;
	}

	private static void writeConfig(Path file, Request request, String java, String remote) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("bind-address", "0.0.0.0");
		properties.setProperty("port", Integer.toString(request.agentPort()));
		properties.setProperty("token", request.token());
		properties.setProperty("machine-name", request.host());
		properties.setProperty("coordinator", request.coordinator());
		properties.setProperty("java", java);
		properties.setProperty("worker-jar", remote + "/mechana-worker.jar");
		properties.setProperty("working-directory", remote + "/data");
		properties.setProperty("max-workers", "32");
		properties.setProperty("capabilities", request.legacyCapabilities());
		properties.setProperty("sandbox-root", request.sandboxRoot());
		properties.setProperty("sandboxed-capabilities", request.sandboxedCapabilities());
		properties.setProperty("stop-timeout-ms", "10000");
		try (var output = Files.newOutputStream(file)) {
			properties.store(output, "Generated by Mechana Worker Control SSH provisioning");
		}
	}

	static String macOsPlist(String java, String remote, Map<String, String> runtimes) {
		var escapedRemote = xml(remote);
		String runtimeArguments = runtimeArguments(runtimes, "<string>", "</string>", SshProvisioner::xml);
		return String.join(System.lineSeparator(), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
				"<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
				"<plist version=\"1.0\"><dict>", "<key>Label</key><string>" + LABEL + "</string>",
				"<key>ProgramArguments</key><array><string>" + xml(java) + "</string>" + runtimeArguments
						+ "<string>-jar</string><string>" + escapedRemote
						+ "/mechana-worker-host-agent.jar</string><string>" + escapedRemote
						+ "/worker-host-agent.properties</string></array>",
				"<key>WorkingDirectory</key><string>" + escapedRemote + "</string>",
				"<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>",
				"<key>StandardOutPath</key><string>" + escapedRemote + "/host-agent.log</string>",
				"<key>StandardErrorPath</key><string>" + escapedRemote + "/host-agent.log</string>", "</dict></plist>",
				"");
	}

	static String linuxService(String java, String remote, Map<String, String> runtimes) {
		String runtimeArguments = runtimeArguments(runtimes, " ", "", SshProvisioner::systemdEscape);
		return String.join(System.lineSeparator(), "[Unit]", "Description=Mechana Worker Host Agent",
				"After=network-online.target", "", "[Service]", "Type=simple", "WorkingDirectory=" + remote,
				"ExecStart=" + java + runtimeArguments + " -jar " + remote + "/mechana-worker-host-agent.jar " + remote
						+ "/worker-host-agent.properties",
				"Restart=on-failure", "RestartSec=2", "", "[Install]", "WantedBy=default.target", "");
	}

	private Map<String, String> discoverRuntimes(Request request, String target, RemoteOs os)
			throws IOException, InterruptedException {
		Map<String, String> runtimes = new LinkedHashMap<>();
		String capabilities = "," + request.sandboxedCapabilities().toLowerCase(Locale.ROOT) + ",";
		if (capabilities.contains(",video-ffmpeg,")) {
			runtimes.put("ffmpeg", requireRuntime(request, target, os, "ffmpeg"));
			runtimes.put("ffprobe", requireRuntime(request, target, os, "ffprobe"));
		}
		if (capabilities.contains(",ocr-tesseract,"))
			runtimes.put("tesseract", requireRuntime(request, target, os, "tesseract"));
		if (capabilities.contains(",blender-render,"))
			runtimes.put("blender", requireRuntime(request, target, os, "blender"));
		return Map.copyOf(runtimes);
	}

	private String requireRuntime(Request request, String target, RemoteOs os, String name)
			throws IOException, InterruptedException {
		String command = runtimeDiscoveryCommand(os, name);
		String path = ssh(request, target, command).lines().filter(line -> line.startsWith("/")).findFirst().orElse("");
		if (path.isBlank())
			throw new IOException("Sandboxed plugin prerequisite is missing on " + request.host() + ": " + name
					+ ". Install it, then use Reinstall + start via SSH again.");
		return path.strip();
	}

	static String runtimeDiscoveryCommand(RemoteOs os, String name) {
		List<String> candidates = new ArrayList<>();
		if (os == RemoteOs.MACOS) {
			candidates.add("/opt/homebrew/bin/" + name);
			candidates.add("/usr/local/bin/" + name);
			if ("blender".equals(name))
				candidates.add("/Applications/Blender.app/Contents/MacOS/Blender");
		} else {
			candidates.add("/usr/bin/" + name);
			candidates.add("/usr/local/bin/" + name);
			if ("blender".equals(name))
				candidates.add("/snap/bin/blender");
		}
		StringBuilder command = new StringBuilder("command -v ").append(name).append(" 2>/dev/null || true");
		for (String candidate : candidates)
			command.append("; test -x ").append(quote(candidate)).append(" && printf '%s\\n' ").append(quote(candidate))
					.append(" || true");
		return command.toString();
	}

	private static String runtimeArguments(Map<String, String> runtimes, String prefix, String suffix,
			java.util.function.UnaryOperator<String> escape) {
		StringBuilder arguments = new StringBuilder();
		for (var runtime : runtimes.entrySet())
			arguments.append(prefix)
					.append(escape.apply("-Dmechana.runtime." + runtime.getKey() + "=" + runtime.getValue()))
					.append(suffix);
		return arguments.toString();
	}

	private static String systemdEscape(String value) {
		return value.replace("\\", "\\\\").replace(" ", "\\x20");
	}
	static String macOsPortReleaseCommand(int port) {
		return "agent_pid=$(lsof -nP -tiTCP:" + port
				+ " -sTCP:LISTEN 2>/dev/null | head -n 1); while [ -n \"$agent_pid\" ]; do "
				+ "agent_command=$(ps -p \"$agent_pid\" -o command=); case \"$agent_command\" in "
				+ "*mechana-worker-host-agent.jar*) kill \"$agent_pid\" 2>/dev/null || true; agent_wait=0; "
				+ "while kill -0 \"$agent_pid\" 2>/dev/null && [ \"$agent_wait\" -lt 50 ]; do sleep 0.1; "
				+ "agent_wait=$((agent_wait + 1)); done; kill -9 \"$agent_pid\" 2>/dev/null || true ;; "
				+ "*) echo \"Port " + port + " is occupied by a non-Mechana process: $agent_command\" >&2; exit 1 ;; "
				+ "esac; agent_pid=$(lsof -nP -tiTCP:" + port + " -sTCP:LISTEN 2>/dev/null | head -n 1); done";
	}

	private static void validate(Request request) throws IOException {
		validateConnection(request);
		if (request.host().isBlank() || request.sshUser().isBlank() || request.token().isBlank()
				|| request.coordinator().isBlank())
			throw new IllegalArgumentException("Host, SSH user, token, and coordinator are required");
		if (!request.remoteDirectory().matches("[A-Za-z0-9._/-]+") || request.remoteDirectory().contains(".."))
			throw new IllegalArgumentException("Remote directory must not contain spaces or '..'");
		if (!request.sandboxRoot().startsWith("/"))
			throw new IllegalArgumentException("Sandbox root must be an absolute remote path");
		if (!Files.isRegularFile(request.agentJar()) || !Files.isRegularFile(request.workerJar()))
			throw new IOException("Build the host-agent and worker JARs before deployment");
		if (request.identityFile() != null && !Files.isRegularFile(request.identityFile()))
			throw new IOException("SSH identity file not found: " + request.identityFile());
	}

	private static void validateConnection(Request request) throws IOException {
		if (request.host().isBlank() || request.sshUser().isBlank())
			throw new IllegalArgumentException("Host and SSH user are required");
		if (request.sshPort() < 1 || request.sshPort() > 65535)
			throw new IllegalArgumentException("SSH port must be between 1 and 65535");
		if (request.identityFile() != null && !Files.isRegularFile(request.identityFile()))
			throw new IOException("SSH identity file not found: " + request.identityFile());
	}

	private static String target(Request request) {
		if (!request.host().matches("[A-Za-z0-9._:-]+") || !request.sshUser().matches("[A-Za-z0-9._-]+"))
			throw new IllegalArgumentException("Invalid SSH host or user");
		return request.sshUser() + "@" + request.host();
	}

	private static String resolveRemoteDirectory(String home, String configured) {
		return configured.startsWith("/") ? configured : home + "/" + configured;
	}

	private static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static String runCommand(List<String> command, Duration timeout) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).start();
		CompletableFuture<String> stdout = readAsync(process.getInputStream());
		CompletableFuture<String> stderr = readAsync(process.getErrorStream());
		boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IOException("Command timed out: " + command.getFirst());
		}
		String output = await(stdout);
		String diagnostic = await(stderr).strip();
		if (process.exitValue() != 0) {
			if (diagnostic.isBlank())
				diagnostic = output.strip();
			throw new IOException(command.getFirst() + " failed (exit " + process.exitValue() + "): " + diagnostic);
		}
		return output;
	}

	private static CompletableFuture<String> readAsync(java.io.InputStream input) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return new String(input.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException failure) {
				throw new CompletionException(failure);
			}
		});
	}

	private static String await(CompletableFuture<String> output) throws IOException {
		try {
			return output.join();
		} catch (CompletionException failure) {
			if (failure.getCause() instanceof IOException io)
				throw io;
			throw failure;
		}
	}

	private static void deleteTree(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
