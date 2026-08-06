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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental macOS backend using Apple's present but deprecated sandbox-exec.
 */
public final class MacOsSandbox extends ProcessSandbox implements PlatformLauncher {
	private static final Path SANDBOX_EXEC = Path.of(System.getProperty("file.separator"), "usr", "bin",
			"sandbox-exec");

	@Override
	public boolean supportsCurrentHost() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
				|| !Files.isExecutable(SANDBOX_EXEC))
			return false;
		try {
			Process probe = new ProcessBuilder(SANDBOX_EXEC.toString(), "-p",
					"(version 1)(deny default)(allow process-exec)(allow file-read*)", "/usr/bin/true").start();
			return probe.waitFor() == 0;
		} catch (IOException failure) {
			return false;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
	@Override
	public PluginSandbox sandbox() {
		return this;
	}

	@Override
	public SandboxCapabilities capabilities(SandboxPolicy policy) {
		Map<SandboxControl, Boolean> controls = new EnumMap<>(SandboxControl.class);
		controls.put(SandboxControl.FILESYSTEM_RESTRICTION, false);
		controls.put(SandboxControl.FILESYSTEM_WRITE_RESTRICTION, supportsCurrentHost());
		controls.put(SandboxControl.HOME_DIRECTORY_DENIAL, supportsCurrentHost());
		controls.put(SandboxControl.NETWORK_DENIAL, supportsCurrentHost() && !policy.networkAllowed());
		controls.put(SandboxControl.TIMEOUT, true);
		controls.put(SandboxControl.PROCESS_TREE_TERMINATION, false);
		return new SandboxCapabilities("macos-sandbox-exec-experimental", controls,
				"sandbox-exec is deprecated by Apple; CPU, memory, scratch-size and process-count limits are not enforced");
	}

	@Override
	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		if (!supportsCurrentHost())
			throw new IOException("macOS sandbox-exec is unavailable");
		List<String> command = new ArrayList<>();
		command.add(SANDBOX_EXEC.toString());
		command.add("-p");
		command.add(profile(request));
		command.addAll(request.command());
		return executeCommand(request, cancellation, command, capabilities(request.policy()));
	}

	String profile(SandboxRequest request) {
		String root = literal(request.workspace().root());
		String userHome = literal(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize());
		if (request.workspace().root()
				.startsWith(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()))
			throw new IllegalArgumentException("macOS sandbox workspaces must be outside the user's home directory");
		StringBuilder profile = new StringBuilder("(version 1)\n(deny default)\n")
				.append("(allow process*)\n(allow sysctl-read)\n(allow mach-lookup)\n(allow iokit-open)\n")
				.append("(allow ipc*)\n").append("(allow file-read*)\n").append("(deny file-read* (subpath \"")
				.append(userHome).append("\"))\n").append("(allow file-read* (subpath \"").append(root)
				.append("/input\"))\n").append("(allow file-read* file-write* (subpath \"").append(root)
				.append("/work\"))\n").append("(allow file-read* file-write* (subpath \"").append(root)
				.append("/output\"))\n").append("(allow file-read* file-write* (subpath \"").append(root)
				.append("/logs\"))\n");
		if (request.policy().networkAllowed())
			profile.append("(allow network*)\n");
		return profile.toString();
	}
	private static String literal(Path path) {
		return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
