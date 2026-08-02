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

		MechanaServer server = new MechanaServer(port, publicUrl, pluginJar, videoPluginJar, fractalPluginJar, 5_000,
				dataDirectory);
		server.onRestart(
				() -> restart(server, port, pluginJar, publicUrl, dataDirectory, videoPluginJar, fractalPluginJar));
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mechana-shutdown"));
		System.out.printf("Mechana server listening on %s%n", publicUrl);
		System.out.printf("Server dashboard: http://127.0.0.1:%d/dashboard%n", server.port());
		System.out.printf("Serving sleep plugin from %s%n", pluginJar.toAbsolutePath());
		System.out.printf("Serving video plugin from %s%n", videoPluginJar.toAbsolutePath());
		System.out.printf("Serving fractal plugin from %s%n", fractalPluginJar.toAbsolutePath());
		System.out.printf("Persisting completed jobs under %s%n", dataDirectory.toAbsolutePath());
		new CountDownLatch(1).await();
	}

	@SuppressFBWarnings(value = "DM_EXIT", justification = "The restart action replaces this dedicated server JVM")
	private static void restart(MechanaServer server, int port, Path pluginJar, String publicUrl, Path dataDirectory,
			Path videoPluginJar, Path fractalPluginJar) {
		server.close();
		try {
			new ProcessBuilder(
					restartCommand(port, pluginJar, publicUrl, dataDirectory, videoPluginJar, fractalPluginJar))
					.inheritIO().start();
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
		return List.copyOf(command);
	}
}
