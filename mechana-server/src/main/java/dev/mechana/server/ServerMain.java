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

package dev.mechana.server;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/** Starts the continuously running Mechana server and scheduler. */
public final class ServerMain {

	private ServerMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
		String publicUrl = args.length > 1 ? args[1] : "http://localhost:" + port;
		Path dataDirectory = args.length > 2 ? Path.of(args[2]) : Path.of(".mechana", "server");
		PluginJars plugins = PluginJars.configured();

		MechanaServer server = new MechanaServer(port, publicUrl, plugins.sleep(), plugins.video(), plugins.fractal(),
				plugins.ocr(), plugins.blender(), plugins.audio(), 5_000, dataDirectory);
		server.onRestart(() -> restart(server, port, publicUrl, dataDirectory));
		server.onStop(() -> stop(server));
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mechana-shutdown"));
		System.out.printf("Mechana server listening on %s%n", publicUrl);
		System.out.printf("Server dashboard: http://127.0.0.1:%d/dashboard%n", server.port());
		System.out.printf("Serving sleep plugin from %s%n", plugins.sleep().toAbsolutePath());
		System.out.printf("Serving video plugin from %s%n", plugins.video().toAbsolutePath());
		System.out.printf("Serving fractal plugin from %s%n", plugins.fractal().toAbsolutePath());
		System.out.printf("Serving OCR plugin from %s%n", plugins.ocr().toAbsolutePath());
		System.out.printf("Serving Blender plugin from %s%n", plugins.blender().toAbsolutePath());
		System.out.printf("Serving audio reverb plugin from %s%n", plugins.audio().toAbsolutePath());
		System.out.printf("Persisting completed jobs under %s%n", dataDirectory.toAbsolutePath());
		new CountDownLatch(1).await();
	}

	@SuppressFBWarnings(value = "DM_EXIT", justification = "The restart action replaces this dedicated server JVM")
	private static void restart(MechanaServer server, int port, String publicUrl, Path dataDirectory) {
		server.close();
		try {
			Optional<List<String>> replacement = replacementCommand(port, publicUrl, dataDirectory);
			if (replacement.isPresent())
				new ProcessBuilder(replacement.orElseThrow()).inheritIO().start();
			System.exit(0);
		} catch (IOException | URISyntaxException failure) {
			System.err.printf("Could not restart Mechana server: %s%n", failure.getMessage());
		}
	}

	@SuppressFBWarnings(value = "DM_EXIT", justification = "The stop action terminates this dedicated server JVM")
	private static void stop(MechanaServer server) {
		if (Boolean.getBoolean("mechana.launchd.managed")) {
			try {
				String uid = new String(new ProcessBuilder("/usr/bin/id", "-u").start().getInputStream().readAllBytes(),
						StandardCharsets.UTF_8).trim();
				Process unload = new ProcessBuilder("/bin/launchctl", "bootout", "gui/" + uid + "/dev.mechana.server")
						.inheritIO().start();
				if (unload.waitFor() != 0) {
					System.err.println("Could not unload the Mechana server LaunchAgent");
				}
			} catch (IOException | InterruptedException failure) {
				if (failure instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				System.err.printf("Could not stop Mechana server: %s%n", failure.getMessage());
			}
			return;
		}
		server.close();
		System.exit(0);
	}

	static Optional<List<String>> replacementCommand(int port, String publicUrl, Path dataDirectory)
			throws URISyntaxException {
		return Boolean.getBoolean("mechana.launchd.managed")
				? Optional.empty()
				: Optional.of(restartCommand(port, publicUrl, dataDirectory));
	}

	static List<String> restartCommand(int port, String publicUrl, Path dataDirectory) throws URISyntaxException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		Path codeSource = Path.of(ServerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		if (java.nio.file.Files.isRegularFile(codeSource)) {
			command.add("-jar");
			command.add(codeSource.toString());
		} else {
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));
			command.add(ServerMain.class.getName());
		}
		command.add(Integer.toString(port));
		command.add(publicUrl);
		command.add(dataDirectory.toAbsolutePath().normalize().toString());
		return List.copyOf(command);
	}

	record PluginJars(Path sleep, Path video, Path fractal, Path ocr, Path blender, Path audio) {
		static PluginJars configured() {
			return new PluginJars(
					pluginPath("sleep", "plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar"),
					pluginPath("video", "plugins/video-ffmpeg-plugin/target/mechana-plugin-video-0.1.0-SNAPSHOT.jar"),
					pluginPath("fractal",
							"plugins/fractal-render-plugin/target/mechana-plugin-fractal-render-0.1.0-SNAPSHOT.jar"),
					pluginPath("ocr",
							"plugins/ocr-tesseract-plugin/target/mechana-plugin-ocr-tesseract-0.1.0-SNAPSHOT.jar"),
					pluginPath("blender",
							"plugins/blender-render-plugin/target/mechana-plugin-blender-render-0.1.0-SNAPSHOT.jar"),
					pluginPath("audio",
							"plugins/audio-reverb-plugin/target/mechana-plugin-audio-reverb-0.1.0-SNAPSHOT.jar"));
		}

		private static Path pluginPath(String id, String defaultPath) {
			String configured = System.getProperty("mechana.plugin." + id + ".jar");
			String packagedApp = System.getProperty("jpackage.app-path");
			Path path = configured != null
					? Path.of(configured)
					: packagedApp != null
							? packagedPluginPath(packagedApp, id)
							: packagedPluginOnClasspath(id).orElse(Path.of(defaultPath));
			return path.toAbsolutePath().normalize();
		}

		private static Optional<Path> packagedPluginOnClasspath(String id) {
			String fileName = pluginFileName(id);
			for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
				Path parent = Path.of(entry).toAbsolutePath().getParent();
				if (parent != null) {
					Path candidate = parent.resolve(fileName);
					if (java.nio.file.Files.isRegularFile(candidate))
						return Optional.of(candidate);
				}
			}
			return Optional.empty();
		}

		private static Path packagedPluginPath(String executable, String id) {
			String fileName = pluginFileName(id);
			Path executablePath = Path.of(executable).toAbsolutePath();
			Path macOsDirectory = Objects.requireNonNull(executablePath.getParent(), "packaged launcher directory");
			Path contentsDirectory = Objects.requireNonNull(macOsDirectory.getParent(), "packaged Contents directory");
			return contentsDirectory.resolve("app").resolve(fileName);
		}

		private static String pluginFileName(String id) {
			return switch (id) {
				case "sleep" -> "mechana-plugin-sleep.jar";
				case "video" -> "mechana-plugin-video.jar";
				case "fractal" -> "mechana-plugin-fractal-render.jar";
				case "ocr" -> "mechana-plugin-ocr-tesseract.jar";
				case "blender" -> "mechana-plugin-blender-render.jar";
				case "audio" -> "mechana-plugin-audio-reverb.jar";
				default -> throw new IllegalArgumentException("Unknown plugin: " + id);
			};
		}
	}
}
