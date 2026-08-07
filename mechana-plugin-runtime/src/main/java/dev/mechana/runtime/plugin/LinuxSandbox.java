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
 * Linux namespace backend implemented with an unprivileged Bubblewrap process.
 */
public final class LinuxSandbox extends ProcessSandbox implements PlatformLauncher {
	private final Path bubblewrap;
	private volatile Boolean supported;

	public LinuxSandbox() {
		this(findExecutable("bwrap"));
	}

	LinuxSandbox(Path bubblewrap) {
		this.bubblewrap = bubblewrap;
	}

	@Override
	public boolean supportsCurrentHost() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
				|| !Files.isExecutable(bubblewrap))
			return false;
		Boolean cached = supported;
		if (cached != null)
			return cached;
		try {
			List<String> probeCommand = new ArrayList<>(List.of(bubblewrap.toString(), "--unshare-user",
					"--unshare-pid", "--unshare-net", "--die-with-parent", "--proc", "/proc", "--dev", "/dev"));
			for (String path : SYSTEM_TREES)
				if (Files.exists(Path.of(path)))
					probeCommand.addAll(List.of("--ro-bind", path, path));
			probeCommand.addAll(List.of("--", "/usr/bin/true"));
			Process probe = new ProcessBuilder(probeCommand).start();
			supported = probe.waitFor() == 0;
		} catch (IOException failure) {
			supported = false;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			supported = false;
		}
		return supported;
	}

	@Override
	public PluginSandbox sandbox() {
		return this;
	}

	@Override
	public SandboxCapabilities capabilities(SandboxPolicy policy) {
		boolean available = supportsCurrentHost();
		Map<SandboxControl, Boolean> controls = new EnumMap<>(SandboxControl.class);
		controls.put(SandboxControl.FILESYSTEM_RESTRICTION, available);
		controls.put(SandboxControl.FILESYSTEM_WRITE_RESTRICTION, available);
		controls.put(SandboxControl.HOME_DIRECTORY_DENIAL, available);
		controls.put(SandboxControl.NETWORK_DENIAL, available && !policy.networkAllowed());
		controls.put(SandboxControl.TIMEOUT, true);
		controls.put(SandboxControl.PROCESS_TREE_TERMINATION, available);
		return new SandboxCapabilities("linux-bubblewrap", controls,
				"CPU, memory, scratch-size, process-count, cgroup and seccomp limits are not yet enforced");
	}

	@Override
	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		if (!supportsCurrentHost())
			throw new IOException("Linux Bubblewrap sandbox is unavailable; install bwrap and enable user namespaces");
		return executeCommand(request, cancellation, command(request), capabilities(request.policy()));
	}

	List<String> command(SandboxRequest request) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(bubblewrap.toString());
		command.addAll(List.of("--unshare-user", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--die-with-parent",
				"--new-session", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
		if (!request.policy().networkAllowed())
			command.add("--unshare-net");
		for (String path : SYSTEM_TREES) {
			Path source = Path.of(path);
			if (Files.exists(source))
				command.addAll(List.of("--ro-bind", path, path));
		}
		Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
		if (!startsInExposedSystemTree(javaHome))
			command.addAll(List.of("--ro-bind", javaHome.toString(), javaHome.toString()));
		AttemptWorkspace workspace = request.workspace();
		command.addAll(List.of("--dir", workspace.root().toString(), "--ro-bind", workspace.input().toString(),
				workspace.input().toString(), "--bind", workspace.work().toString(), workspace.work().toString(),
				"--bind", workspace.output().toString(), workspace.output().toString(), "--bind",
				workspace.logs().toString(), workspace.logs().toString(), "--chdir", workspace.work().toString(),
				"--"));
		command.addAll(request.command());
		return command;
	}

	private static boolean startsInExposedSystemTree(Path path) {
		return List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/opt").stream().map(Path::of)
				.anyMatch(path::startsWith);
	}

	private static final List<String> SYSTEM_TREES = List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/opt");

	private static Path findExecutable(String name) {
		String path = System.getenv().getOrDefault("PATH", "");
		return java.util.Arrays.stream(path.split(java.io.File.pathSeparator)).filter(part -> !part.isBlank())
				.map(Path::of).map(directory -> directory.resolve(name)).filter(Files::isExecutable).findFirst()
				.orElse(Path.of(name));
	}
}
