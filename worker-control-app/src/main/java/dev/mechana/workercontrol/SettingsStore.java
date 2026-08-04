package dev.mechana.workercontrol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

final class SettingsStore {
	record Settings(List<String> hosts, String lastHost, int port, String token, int count) {
	}
	private final Path file;
	SettingsStore(Path file) {
		this.file = file;
	}
	static SettingsStore userDefault() {
		return new SettingsStore(Path.of(System.getProperty("user.home"), ".mechana", "worker-control.properties"));
	}

	Settings load() throws IOException {
		Properties p = new Properties();
		if (Files.isRegularFile(file))
			try (InputStream input = Files.newInputStream(file)) {
				p.load(input);
			}
		List<String> hosts = new ArrayList<>();
		for (int i = 0;; i++) {
			String host = p.getProperty("host." + i);
			if (host == null)
				break;
			if (!host.isBlank())
				hosts.add(host);
		}
		return new Settings(List.copyOf(hosts), p.getProperty("last-host", "localhost"),
				Integer.parseInt(p.getProperty("port", "8790")), p.getProperty("token", ""),
				Integer.parseInt(p.getProperty("count", "1")));
	}

	void save(Settings settings) throws IOException {
		Properties p = new Properties();
		List<String> hosts = new ArrayList<>(new LinkedHashSet<>(settings.hosts()));
		for (int i = 0; i < hosts.size(); i++)
			p.setProperty("host." + i, hosts.get(i));
		p.setProperty("last-host", settings.lastHost());
		p.setProperty("port", Integer.toString(settings.port()));
		p.setProperty("token", settings.token());
		p.setProperty("count", Integer.toString(settings.count()));
		Path parent = file.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		try (OutputStream output = Files.newOutputStream(file)) {
			p.store(output, "Mechana worker control settings; protect this file because it contains the agent token");
		}
	}
}
