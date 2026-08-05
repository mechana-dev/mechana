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
	record Settings(List<String> hosts, String lastHost, int port, String token, int count,
			AgentClient.LaunchMode launchMode, String capabilities, String sshUser, int sshPort, String identityFile,
			boolean acceptNewHostKey, String coordinator, String remoteDirectory, String agentJar, String workerJar,
			String sandboxRoot) {
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
				Integer.parseInt(p.getProperty("count", "1")),
				AgentClient.LaunchMode.valueOf(p.getProperty("launch-mode", "SANDBOXED")),
				p.getProperty("capabilities", "fractal-render"),
				p.getProperty("ssh-user", System.getProperty("user.name")),
				Integer.parseInt(p.getProperty("ssh-port", "22")), p.getProperty("identity-file", ""),
				Boolean.parseBoolean(p.getProperty("accept-new-host-key", "false")),
				p.getProperty("coordinator", "http://127.0.0.1:8787"),
				p.getProperty("remote-directory", ".mechana/host-agent"),
				p.getProperty("agent-jar", "worker-host-agent/target/mechana-worker-host-agent.jar"),
				p.getProperty("worker-jar", "mechana-worker/target/mechana-worker.jar"),
				p.getProperty("sandbox-root", "/private/tmp/mechana-sandbox"));
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
		p.setProperty("launch-mode", settings.launchMode().name());
		p.setProperty("capabilities", settings.capabilities());
		p.setProperty("ssh-user", settings.sshUser());
		p.setProperty("ssh-port", Integer.toString(settings.sshPort()));
		p.setProperty("identity-file", settings.identityFile());
		p.setProperty("accept-new-host-key", Boolean.toString(settings.acceptNewHostKey()));
		p.setProperty("coordinator", settings.coordinator());
		p.setProperty("remote-directory", settings.remoteDirectory());
		p.setProperty("agent-jar", settings.agentJar());
		p.setProperty("worker-jar", settings.workerJar());
		p.setProperty("sandbox-root", settings.sandboxRoot());
		Path parent = file.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		try (OutputStream output = Files.newOutputStream(file)) {
			p.store(output, "Mechana worker control settings; protect this file because it contains the agent token");
		}
	}
}
