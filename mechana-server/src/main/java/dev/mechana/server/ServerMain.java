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
		Path pluginJar = args.length > 1
				? Path.of(args[1])
				: Path.of("plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar");
		String publicUrl = args.length > 2 ? args[2] : "http://localhost:" + port;
		Path dataDirectory = args.length > 3 ? Path.of(args[3]) : Path.of(".mechana", "server");
		Path videoPluginJar = args.length > 4
				? Path.of(args[4])
				: Path.of("plugins/video-ffmpeg-plugin/target/mechana-plugin-video-0.1.0-SNAPSHOT.jar");
		Path fractalPluginJar = args.length > 5
				? Path.of(args[5])
				: Path.of("plugins/fractal-render-plugin/target/mechana-plugin-fractal-render-0.1.0-SNAPSHOT.jar");
		Path ocrPluginJar = args.length > 6
				? Path.of(args[6])
				: Path.of("plugins/ocr-tesseract-plugin/target/mechana-plugin-ocr-tesseract-0.1.0-SNAPSHOT.jar");
		Path blenderPluginJar = args.length > 7
				? Path.of(args[7])
				: Path.of("plugins/blender-render-plugin/target/mechana-plugin-blender-render-0.1.0-SNAPSHOT.jar");

		MechanaServer server = new MechanaServer(port, publicUrl, pluginJar, videoPluginJar, fractalPluginJar,
				ocrPluginJar, blenderPluginJar, 5_000, dataDirectory);
		server.onRestart(() -> restart(server, port, pluginJar, publicUrl, dataDirectory, videoPluginJar,
				fractalPluginJar, ocrPluginJar, blenderPluginJar));
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mechana-shutdown"));
		System.out.printf("Mechana server listening on %s%n", publicUrl);
		System.out.printf("Server dashboard: http://127.0.0.1:%d/dashboard%n", server.port());
		System.out.printf("Serving sleep plugin from %s%n", pluginJar.toAbsolutePath());
		System.out.printf("Serving video plugin from %s%n", videoPluginJar.toAbsolutePath());
		System.out.printf("Serving fractal plugin from %s%n", fractalPluginJar.toAbsolutePath());
		System.out.printf("Serving OCR plugin from %s%n", ocrPluginJar.toAbsolutePath());
		System.out.printf("Serving Blender plugin from %s%n", blenderPluginJar.toAbsolutePath());
		System.out.printf("Persisting completed jobs under %s%n", dataDirectory.toAbsolutePath());
		new CountDownLatch(1).await();
	}

	@SuppressFBWarnings(value = "DM_EXIT", justification = "The restart action replaces this dedicated server JVM")
	private static void restart(MechanaServer server, int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar, Path fractalPluginJar, Path ocrPluginJar, Path blenderPluginJar) {
		server.close();
		try {
			new ProcessBuilder(restartCommand(port, pluginJar, publicUrl, dataDirectory, videoPluginJar,
					fractalPluginJar, ocrPluginJar, blenderPluginJar)).inheritIO().start();
			System.exit(0);
		} catch (IOException | URISyntaxException failure) {
			System.err.printf("Could not restart Mechana server: %s%n", failure.getMessage());
		}
	}

	static List<String> restartCommand(int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar) throws URISyntaxException {
		return restartCommand(port, pluginJar, publicUrl, dataDirectory, videoPluginJar, videoPluginJar);
	}

	static List<String> restartCommand(int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar, Path fractalPluginJar) throws URISyntaxException {
		return restartCommand(port, pluginJar, publicUrl, dataDirectory, videoPluginJar, fractalPluginJar,
				fractalPluginJar);
	}

	static List<String> restartCommand(int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar, Path fractalPluginJar, Path ocrPluginJar) throws URISyntaxException {
		return restartCommand(port, pluginJar, publicUrl, dataDirectory, videoPluginJar, fractalPluginJar, ocrPluginJar,
				ocrPluginJar);
	}

	static List<String> restartCommand(int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar, Path fractalPluginJar, Path ocrPluginJar, Path blenderPluginJar)
			throws URISyntaxException {
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
		command.add(pluginJar.toAbsolutePath().normalize().toString());
		command.add(publicUrl);
		command.add(dataDirectory.toAbsolutePath().normalize().toString());
		command.add(videoPluginJar.toAbsolutePath().normalize().toString());
		command.add(fractalPluginJar.toAbsolutePath().normalize().toString());
		command.add(ocrPluginJar.toAbsolutePath().normalize().toString());
		command.add(blenderPluginJar.toAbsolutePath().normalize().toString());
		return List.copyOf(command);
	}
}
