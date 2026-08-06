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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Windows AppContainer and Job Object sandbox backend. */
public final class WindowsSandbox extends ProcessSandbox implements PlatformLauncher {
	public static final String LAUNCHER_PROPERTY = "mechana.windows.sandbox.launcher";

	@Override
	public boolean supportsCurrentHost() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
			return false;
		Path launcher = launcher();
		if (!Files.isRegularFile(launcher))
			return false;
		try {
			Process probe = new ProcessBuilder(launcher.toString(), "--probe").redirectErrorStream(true).start();
			return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
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
		boolean active = supportsCurrentHost();
		Map<SandboxControl, Boolean> controls = new EnumMap<>(SandboxControl.class);
		controls.put(SandboxControl.FILESYSTEM_RESTRICTION, active);
		controls.put(SandboxControl.FILESYSTEM_WRITE_RESTRICTION, active);
		controls.put(SandboxControl.HOME_DIRECTORY_DENIAL, active);
		controls.put(SandboxControl.NETWORK_DENIAL, active && !policy.networkAllowed());
		controls.put(SandboxControl.MEMORY_LIMIT, active);
		controls.put(SandboxControl.CPU_LIMIT, active);
		controls.put(SandboxControl.PROCESS_LIMIT, active);
		controls.put(SandboxControl.TIMEOUT, true);
		controls.put(SandboxControl.PROCESS_TREE_TERMINATION, active);
		return new SandboxCapabilities("windows-appcontainer-job", controls,
				"AppContainer filesystem/network isolation with Job Object resource and lifecycle limits");
	}

	@Override
	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		if (!supportsCurrentHost())
			throw new IOException("Windows sandbox launcher is unavailable or its live probe failed");
		return executeCommand(request, cancellation, command(request), capabilities(request.policy()));
	}

	List<String> command(SandboxRequest request) {
		SandboxPolicy policy = request.policy();
		List<String> command = new ArrayList<>();
		command.add(launcher().toString());
		option(command, "--workspace", request.workspace().root().toString());
		option(command, "--memory", Long.toString(policy.memoryBytes()));
		option(command, "--cpu", Integer.toString(policy.cpuCount()));
		option(command, "--host-cpu", Integer.toString(Runtime.getRuntime().availableProcessors()));
		option(command, "--processes", Integer.toString(policy.maxProcesses()));
		readTree(command, Path.of(System.getProperty("java.home")));
		for (String runtime : List.of("ffmpeg", "ffprobe", "tesseract", "blender")) {
			String configured = System.getProperty("mechana.runtime." + runtime, "").strip();
			if (!configured.isEmpty())
				readPath(command, Path.of(configured).toAbsolutePath().normalize().getParent());
		}
		command.add("--");
		command.addAll(request.command());
		return List.copyOf(command);
	}

	private static void readPath(List<String> command, Path path) {
		if (path != null)
			option(command, "--read", path.toAbsolutePath().normalize().toString());
	}

	private static void readTree(List<String> command, Path path) {
		if (path != null)
			option(command, "--read-tree", path.toAbsolutePath().normalize().toString());
	}

	private static void option(List<String> command, String name, String value) {
		command.add(name);
		command.add(value);
	}

	private static Path launcher() {
		return Path.of(System.getProperty(LAUNCHER_PROPERTY, "mechana-windows-sandbox.exe")).toAbsolutePath()
				.normalize();
	}

	public static void main(String[] args) {
		WindowsSandbox sandbox = new WindowsSandbox();
		SandboxPolicy policy = new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 256L * 1024 * 1024, 1024 * 1024,
				Duration.ofSeconds(5), 4);
		if (!sandbox.supportsCurrentHost())
			throw new IllegalStateException("Windows AppContainer/Job Object probe failed");
		System.out.println("backend=" + sandbox.capabilities(policy).backend());
		sandbox.capabilities(policy).enforced().forEach((control, enforced) -> System.out
				.println("control." + control.name().toLowerCase(Locale.ROOT) + "=" + enforced));
		System.out.println("validation=passed");
	}
}
