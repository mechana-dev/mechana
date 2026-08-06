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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshProvisionerTest {
	@TempDir
	Path temporary;

	@Test
	void deploysMacOsLaunchAgentAndArtifactsUsingBatchSsh() throws Exception {
		Path agent = Files.writeString(temporary.resolve("agent.jar"), "agent");
		Path worker = Files.writeString(temporary.resolve("worker.jar"), "worker");
		List<List<String>> commands = new ArrayList<>();
		List<String> uploadedText = new ArrayList<>();
		SshProvisioner provisioner = new SshProvisioner((command, timeout) -> {
			commands.add(List.copyOf(command));
			if (command.getFirst().equals("scp")) {
				Path source = Path.of(command.get(command.size() - 2));
				if (!source.equals(agent) && !source.equals(worker))
					uploadedText.add(Files.readString(source));
				return "";
			}
			String remote = command.getLast();
			if (remote.equals("uname -s"))
				return "Darwin\n";
			if (remote.equals("pwd"))
				return "/Users/remote\n";
			if (remote.equals("command -v java"))
				return "/usr/bin/java\n";
			return "";
		});
		SshProvisioner.Result result = provisioner.deploy(request(agent, worker));
		assertEquals(SshProvisioner.RemoteOs.MACOS, result.os());
		assertTrue(commands.stream().anyMatch(command -> command.getFirst().equals("scp")));
		assertTrue(commands.stream().flatMap(List::stream).anyMatch(value -> value.contains("launchctl bootstrap")));
		assertTrue(commands.stream().map(List::getLast)
				.anyMatch(value -> value.contains("lsof -nP -tiTCP:8790")
						&& value.contains("*mechana-worker-host-agent.jar*")
						&& value.contains("occupied by a non-Mechana process")));
		assertTrue(uploadedText.stream().anyMatch(value -> value.contains("sandbox-root=/private/tmp/mechana")));
		assertTrue(uploadedText.stream().anyMatch(value -> value.contains("dev.mechana.worker-host-agent")));
		assertTrue(commands.stream().flatMap(List::stream).anyMatch(value -> value.equals("BatchMode=yes")));
		assertTrue(commands.stream().filter(command -> command.getFirst().equals("ssh"))
				.anyMatch(command -> hasOption(command, "-p", "2222")));
		assertTrue(commands.stream().filter(command -> command.getFirst().equals("scp"))
				.anyMatch(command -> hasOption(command, "-P", "2222")));
		assertTrue(commands.stream().filter(command -> command.getFirst().equals("scp")).map(List::getLast)
				.anyMatch(value -> value.equals(
						"mark@mba.example:/Users/remote/Library/LaunchAgents/dev.mechana.worker-host-agent.plist")));
		assertTrue(commands.stream().filter(command -> command.getFirst().equals("scp")).map(List::getLast)
				.noneMatch(value -> value.contains("$HOME")));
	}

	@Test
	void rejectsUnsupportedSshPlatform() throws Exception {
		Path agent = Files.writeString(temporary.resolve("agent.jar"), "agent");
		Path worker = Files.writeString(temporary.resolve("worker.jar"), "worker");
		SshProvisioner provisioner = new SshProvisioner((command, timeout) -> "Windows_NT\n");
		assertThrows(java.io.IOException.class, () -> provisioner.deploy(request(agent, worker)));
	}

	@Test
	void serviceTemplatesContainRestartAndEscapedMacValues() {
		Map<String, String> runtimes = Map.of("ffmpeg", "/opt/homebrew/bin/ffmpeg", "blender",
				"/Applications/Blender.app/Contents/MacOS/Blender");
		String linux = SshProvisioner.linuxService("/usr/bin/java", "/home/mechana", runtimes);
		String mac = SshProvisioner.macOsPlist("/Java & Tools/java", "/Users/a<b", runtimes);
		assertTrue(linux.contains("Restart=on-failure"));
		assertTrue(linux.contains("-Dmechana.runtime.ffmpeg=/opt/homebrew/bin/ffmpeg"));
		assertTrue(linux.contains("Blender.app/Contents/MacOS/Blender"));
		assertTrue(mac.contains("/Java &amp; Tools/java"));
		assertTrue(mac.contains("-Dmechana.runtime.ffmpeg=/opt/homebrew/bin/ffmpeg"));
		assertTrue(mac.contains("-Dmechana.runtime.blender=/Applications/Blender.app/Contents/MacOS/Blender"));
	}

	@Test
	void discoversHomebrewAndApplicationRuntimesOnMacOs() {
		String ffmpeg = SshProvisioner.runtimeDiscoveryCommand(SshProvisioner.RemoteOs.MACOS, "ffmpeg");
		String blender = SshProvisioner.runtimeDiscoveryCommand(SshProvisioner.RemoteOs.MACOS, "blender");
		assertTrue(ffmpeg.contains("command -v ffmpeg"));
		assertTrue(ffmpeg.contains("/opt/homebrew/bin/ffmpeg"));
		assertTrue(blender.contains("/Applications/Blender.app/Contents/MacOS/Blender"));
	}

	@Test
	void staleAgentCleanupIsRestrictedToVerifiedMechanaListeners() throws Exception {
		String command = SshProvisioner.macOsPortReleaseCommand(21012);
		assertTrue(command.contains("lsof -nP -tiTCP:21012"));
		assertTrue(command.contains("ps -p \"$agent_pid\" -o command="));
		assertTrue(command.contains("*mechana-worker-host-agent.jar*"));
		assertTrue(command.contains("Port 21012 is occupied by a non-Mechana process"));
		assertEquals("", SshProvisioner.runCommand(List.of("/bin/sh", "-n", "-c", command), Duration.ofSeconds(5)));
	}

	@Test
	void staleAgentCleanupIsRestrictedToVerifiedMechanaListeners() throws Exception {
		String command = SshProvisioner.macOsPortReleaseCommand(21012);
		assertTrue(command.contains("lsof -nP -tiTCP:21012"));
		assertTrue(command.contains("ps -p \"$agent_pid\" -o command="));
		assertTrue(command.contains("*mechana-worker-host-agent.jar*"));
		assertTrue(command.contains("Port 21012 is occupied by a non-Mechana process"));
		assertEquals("", SshProvisioner.runCommand(List.of("/bin/sh", "-n", "-c", command), Duration.ofSeconds(5)));
	}

	@Test
	void restartsExistingMacOsAgentUsingResolvedHome() throws Exception {
		Path agent = Files.writeString(temporary.resolve("agent.jar"), "agent");
		Path worker = Files.writeString(temporary.resolve("worker.jar"), "worker");
		List<List<String>> commands = new ArrayList<>();
		SshProvisioner provisioner = new SshProvisioner((command, timeout) -> {
			commands.add(List.copyOf(command));
			if (command.getLast().equals("uname -s"))
				return "Darwin\n";
			if (command.getLast().equals("pwd"))
				return "/Users/remote\n";
			return "";
		});
		provisioner.restart(request(agent, worker));
		assertTrue(commands.stream().map(List::getLast)
				.anyMatch(value -> value.contains("launchctl kickstart -k") && value.contains("launchctl bootstrap")
						&& !value.contains("launchctl print")
						&& value.contains("/Users/remote/Library/LaunchAgents/dev.mechana.worker-host-agent.plist")
						&& !value.contains("$HOME")));
	}

	@Test
	void keepsSuccessfulCommandDiagnosticsOutOfStdout() throws Exception {
		Path command = Files.writeString(temporary.resolve("ssh-warning.sh"),
				"#!/bin/sh\nprintf 'post-quantum warning\\n' >&2\nprintf 'Darwin\\n'\n");
		assertTrue(command.toFile().setExecutable(true));
		assertEquals("Darwin\n", SshProvisioner.runCommand(List.of(command.toString()), Duration.ofSeconds(5)));
	}

	@Test
	void includesCommandStderrWhenTheCommandFails() throws Exception {
		Path command = Files.writeString(temporary.resolve("ssh-failure.sh"),
				"#!/bin/sh\nprintf 'connection refused\\n' >&2\nexit 7\n");
		assertTrue(command.toFile().setExecutable(true));
		java.io.IOException failure = assertThrows(java.io.IOException.class,
				() -> SshProvisioner.runCommand(List.of(command.toString()), Duration.ofSeconds(5)));
		assertTrue(failure.getMessage().contains("connection refused"));
	}

	private SshProvisioner.Request request(Path agent, Path worker) {
		return new SshProvisioner.Request("mba.example", "mark", 2222, null, false, agent, worker,
				".mechana/host-agent", "http://coordinator:8787", 8790, "secret", "sleep,fractal-render",
				"fractal-render", "/private/tmp/mechana");
	}

	private static boolean hasOption(List<String> command, String option, String value) {
		int index = command.indexOf(option);
		return index >= 0 && index + 1 < command.size() && command.get(index + 1).equals(value);
	}
}
