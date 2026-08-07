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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Windows AppContainer and Job Object sandbox backend. */
public final class WindowsSandbox extends ProcessSandbox implements PlatformLauncher {
	private static final String LAUNCHER_RESOURCE = "/dev/mechana/runtime/plugin/windows-sandbox-launcher.ps1";
	private volatile Boolean supported;
	private volatile String supportFailure = "not probed";

	@Override
	public boolean supportsCurrentHost() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
			return false;
		Boolean cached = supported;
		if (cached != null)
			return cached;
		Path probeRoot = null;
		try {
			Path javaBinary = privateJavaHome().resolve("bin").resolve("java.exe");
			if (!Files.isRegularFile(javaBinary)) {
				supportFailure = "Private Mechana Java runtime is missing: " + javaBinary;
				supported = false;
				return false;
			}
			Path probeBase = mechanaHome().resolve("sandbox");
			Files.createDirectories(probeBase);
			probeRoot = Files.createTempDirectory(probeBase, "probe-");
			AttemptWorkspace workspace = AttemptWorkspace.create(probeRoot, "probe", "probe");
			Path launcher = extractLauncher(workspace);
			List<String> command = launcherCommand(launcher, workspace,
					new SandboxPolicy(TrustMode.SANDBOXED, false, 1, 64L * 1024 * 1024, 64L * 1024 * 1024,
							java.time.Duration.ofSeconds(10), 2),
					List.of(System.getenv().getOrDefault("ComSpec", "C:\\Windows\\System32\\cmd.exe"), "/d", "/c",
							"exit", "0"));
			Process probe = new ProcessBuilder(command).directory(workspace.work().toFile()).redirectErrorStream(true)
					.start();
			String diagnostic = new String(probe.getInputStream().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			supported = probe.waitFor() == 0;
			supportFailure = supported ? "" : diagnostic.strip();
		} catch (IOException failure) {
			supported = false;
			supportFailure = failure.toString();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			supported = false;
			supportFailure = interrupted.toString();
		} finally {
			if (probeRoot != null)
				deleteTree(probeRoot);
		}
		return supported;
	}

	String supportFailure() {
		return supportFailure;
	}

	@Override
	public PluginSandbox sandbox() {
		return this;
	}

	@Override
	public SandboxCapabilities capabilities(SandboxPolicy policy) {
		boolean available = supportsCurrentHost();
		Map<SandboxControl, Boolean> controls = new EnumMap<>(SandboxControl.class);
		controls.put(SandboxControl.FILESYSTEM_RESTRICTION, false);
		controls.put(SandboxControl.FILESYSTEM_WRITE_RESTRICTION, available);
		controls.put(SandboxControl.HOME_DIRECTORY_DENIAL, available);
		controls.put(SandboxControl.NETWORK_DENIAL, available && !policy.networkAllowed());
		controls.put(SandboxControl.MEMORY_LIMIT, available);
		controls.put(SandboxControl.CPU_LIMIT, available);
		controls.put(SandboxControl.PROCESS_LIMIT, available);
		controls.put(SandboxControl.TIMEOUT, true);
		controls.put(SandboxControl.PROCESS_TREE_TERMINATION, available);
		return new SandboxCapabilities("windows-appcontainer-job", controls,
				"AppContainer permits Windows runtime resources; scratch-byte limits are not yet enforced");
	}

	@Override
	public SandboxResult execute(SandboxRequest request, AtomicBoolean cancellation)
			throws IOException, InterruptedException {
		if (!supportsCurrentHost())
			throw new IOException("Windows AppContainer sandbox is unavailable on this host");
		Path workspace = request.workspace().root().toAbsolutePath().normalize();
		if (!workspace.startsWith(mechanaHome()))
			throw new IOException("Windows sandbox workspace must be below " + mechanaHome());
		Path launcher = extractLauncher(request.workspace());
		return executeCommand(request, cancellation, launcherCommand(launcher, request.workspace(), request.policy(),
				request.command(), request.runtimeReadOnlyPaths()), capabilities(request.policy()));
	}

	List<String> launcherCommand(Path launcher, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> childCommand) {
		return launcherCommand(launcher, workspace, policy, childCommand, List.of());
	}

	List<String> launcherCommand(Path launcher, AttemptWorkspace workspace, SandboxPolicy policy,
			List<String> childCommand, List<Path> runtimeReadOnlyPaths) {
		if (policy.networkAllowed())
			throw new IllegalArgumentException("Windows AppContainer network grants are not implemented");
		List<String> command = new ArrayList<>();
		command.add(powershell());
		List<String> effectiveChildCommand = new ArrayList<>(childCommand);
		if (!effectiveChildCommand.isEmpty()) {
			String commandPath = effectiveChildCommand.getFirst().replace('\\', '/');
			String executable = commandPath.substring(commandPath.lastIndexOf('/') + 1);
			if (executable.equalsIgnoreCase("java") || executable.equalsIgnoreCase("java.exe"))
				effectiveChildCommand.set(0, privateJavaHome().resolve("bin").resolve("java.exe").toString());
		}
		String encodedChildCommand = Base64.getEncoder().encodeToString(
				String.join("\0", effectiveChildCommand).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		List<String> runtimePaths = runtimeReadOnlyPaths.stream().map(Path::toAbsolutePath).map(Path::normalize)
				.map(path -> {
					if (!path.startsWith(mechanaHome().resolve("runtime")))
						throw new IllegalArgumentException(
								"Windows sandbox runtime path must be below " + mechanaHome().resolve("runtime"));
					return path.toString();
				}).distinct().toList();
		String encodedRuntimePaths = Base64.getEncoder()
				.encodeToString(String.join("\0", runtimePaths).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		command.addAll(List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
				launcher.toString(), "-Workspace", workspace.root().toString(), "-MechanaHome",
				mechanaHome().toString(), "-JavaHome", privateJavaHome().toString(), "-MemoryBytes",
				Long.toString(policy.memoryBytes()), "-CpuCount", Integer.toString(policy.cpuCount()), "-MaxProcesses",
				Integer.toString(policy.maxProcesses()), "-ChildCommandBase64", encodedChildCommand,
				"-RuntimePathsBase64", encodedRuntimePaths));
		return List.copyOf(command);
	}

	private static Path mechanaHome() {
		String configured = System.getProperty("mechana.windows.home");
		if (configured != null && !configured.isBlank())
			return Path.of(configured).toAbsolutePath().normalize();
		return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "Mechana").toAbsolutePath()
				.normalize();
	}

	private static Path privateJavaHome() {
		return mechanaHome().resolve("runtime").resolve("java-25");
	}

	private static Path extractLauncher(AttemptWorkspace workspace) throws IOException {
		Path launcher = workspace.work().resolve("windows-sandbox-launcher.ps1");
		try (InputStream input = WindowsSandbox.class.getResourceAsStream(LAUNCHER_RESOURCE)) {
			if (input == null)
				throw new IOException("Windows sandbox launcher resource is missing");
			Files.copy(input, launcher, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		return launcher;
	}

	private static String powershell() {
		String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
		return Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
	}

	private static void deleteTree(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Probe cleanup is best effort.
				}
			});
		} catch (IOException ignored) {
			// Probe cleanup is best effort.
		}
	}
}
