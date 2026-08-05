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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
				plugins.ocr(), plugins.blender(), 5_000, dataDirectory);
		server.onRestart(() -> restart(server, port, publicUrl, dataDirectory));
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mechana-shutdown"));
		System.out.printf("Mechana server listening on %s%n", publicUrl);
		System.out.printf("Server dashboard: http://127.0.0.1:%d/dashboard%n", server.port());
		System.out.printf("Serving sleep plugin from %s%n", plugins.sleep().toAbsolutePath());
		System.out.printf("Serving video plugin from %s%n", plugins.video().toAbsolutePath());
		System.out.printf("Serving fractal plugin from %s%n", plugins.fractal().toAbsolutePath());
		System.out.printf("Serving OCR plugin from %s%n", plugins.ocr().toAbsolutePath());
		System.out.printf("Serving Blender plugin from %s%n", plugins.blender().toAbsolutePath());
		System.out.printf("Persisting completed jobs under %s%n", dataDirectory.toAbsolutePath());
		new CountDownLatch(1).await();
	}

	@SuppressFBWarnings(value = "DM_EXIT", justification = "The restart action replaces this dedicated server JVM")
	private static void restart(MechanaServer server, int port, String publicUrl, Path dataDirectory) {
		server.close();
		try {
			new ProcessBuilder(restartCommand(port, publicUrl, dataDirectory)).inheritIO().start();
			System.exit(0);
		} catch (IOException | URISyntaxException failure) {
			System.err.printf("Could not restart Mechana server: %s%n", failure.getMessage());
		}
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

	record PluginJars(Path sleep, Path video, Path fractal, Path ocr, Path blender) {
		static PluginJars configured() {
			return new PluginJars(
					pluginPath("sleep", "plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar"),
					pluginPath("video", "plugins/video-ffmpeg-plugin/target/mechana-plugin-video-0.1.0-SNAPSHOT.jar"),
					pluginPath("fractal",
							"plugins/fractal-render-plugin/target/mechana-plugin-fractal-render-0.1.0-SNAPSHOT.jar"),
					pluginPath("ocr",
							"plugins/ocr-tesseract-plugin/target/mechana-plugin-ocr-tesseract-0.1.0-SNAPSHOT.jar"),
					pluginPath("blender",
							"plugins/blender-render-plugin/target/mechana-plugin-blender-render-0.1.0-SNAPSHOT.jar"));
		}

		private static Path pluginPath(String id, String defaultPath) {
			return Path.of(System.getProperty("mechana.plugin." + id + ".jar", defaultPath)).toAbsolutePath()
					.normalize();
		}
	}
}
