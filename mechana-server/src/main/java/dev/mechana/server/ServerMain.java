package dev.mechana.server;

import java.io.IOException;
import java.nio.file.Path;
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

		MechanaServer server = new MechanaServer(port, publicUrl, pluginJar, 5_000, dataDirectory);
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mechana-shutdown"));
		System.out.printf("Mechana server listening on %s%n", publicUrl);
		System.out.printf("Server dashboard: http://127.0.0.1:%d/dashboard%n", server.port());
		System.out.printf("Serving sleep plugin from %s%n", pluginJar.toAbsolutePath());
		System.out.printf("Persisting completed jobs under %s%n", dataDirectory.toAbsolutePath());
		new CountDownLatch(1).await();
	}
}
