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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class SettingsStore {
	static final String ALL_SUPPORTED_PLUGINS = "sleep,video-ffmpeg,fractal-render,ocr-tesseract,blender-render";
	static final String FLEET_COORDINATOR = "http://marks-macbook-air-m4:8787";
	private static final int CURRENT_VERSION = 3;
	private static final Map<String, SshDefaults> KNOWN_HOSTS = Map.of("marks-macbook-air-m4",
			new SshDefaults("markvita", 22), "rocinante", new SshDefaults("markvita", 21012), "srv959600",
			new SshDefaults("root", 22), "hyperion", new SshDefaults("markf", 22));

	record HostSettings(int port, String token, int count, AgentClient.LaunchMode launchMode, String capabilities,
			String sshUser, int sshPort, String identityFile, boolean acceptNewHostKey, String coordinator,
			String remoteDirectory, String agentJar, String workerJar, String sandboxRoot,
			String windowsSandboxLauncher) implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
	}
	record Settings(List<String> hosts, String lastHost, Map<String, HostSettings> profiles) {
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
		String lastHost = p.getProperty("last-host", "localhost");
		int version = Integer.parseInt(p.getProperty("settings-version", "1"));
		if (!hosts.contains(lastHost))
			hosts.add(lastHost);
		KNOWN_HOSTS.keySet().stream().sorted().filter(host -> !hosts.contains(host)).forEach(hosts::add);
		HostSettings legacy = readProfile(p, "", defaults());
		Map<String, HostSettings> profiles = new LinkedHashMap<>();
		for (int i = 0; i < hosts.size(); i++) {
			String prefix = "profile." + i + ".";
			if (p.containsKey(prefix + "port")) {
				HostSettings profile = readProfile(p, prefix, defaultsFor(hosts.get(i)));
				profiles.put(hosts.get(i), migrateKnownCoordinator(hosts.get(i), profile, version));
			} else if (hosts.get(i).equals(lastHost))
				profiles.put(hosts.get(i), migrateLegacyProfile(hosts.get(i), legacy));
			else
				profiles.put(hosts.get(i), defaultsFor(hosts.get(i)));
		}
		return new Settings(List.copyOf(hosts), lastHost, Map.copyOf(profiles));
	}

	void save(Settings settings) throws IOException {
		Properties p = new Properties();
		p.setProperty("settings-version", Integer.toString(CURRENT_VERSION));
		List<String> hosts = new ArrayList<>(new LinkedHashSet<>(settings.hosts()));
		for (int i = 0; i < hosts.size(); i++)
			p.setProperty("host." + i, hosts.get(i));
		p.setProperty("last-host", settings.lastHost());
		for (int i = 0; i < hosts.size(); i++) {
			HostSettings profile = settings.profiles().get(hosts.get(i));
			if (profile != null)
				writeProfile(p, "profile." + i + ".", profile);
		}
		Path parent = file.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		try (OutputStream output = Files.newOutputStream(file)) {
			p.store(output, "Mechana worker control settings; protect this file because it contains the agent token");
		}
	}

	static HostSettings defaults() {
		return new HostSettings(8790, "", 1, AgentClient.LaunchMode.SANDBOXED, ALL_SUPPORTED_PLUGINS,
				System.getProperty("user.name"), 22, "", false, "http://127.0.0.1:8787", "~/.mechana/host-agent",
				"worker-host-agent/target/mechana-worker-host-agent.jar", "mechana-worker/target/mechana-worker.jar",
				"~/.mechana/sandbox",
				"windows-sandbox-launcher/bin/Release/net10.0-windows/win-arm64/publish/mechana-windows-sandbox.exe");
	}

	static HostSettings defaultsFor(String host) {
		HostSettings defaults = defaults();
		SshDefaults ssh = KNOWN_HOSTS.get(host);
		if (ssh == null)
			return defaults;
		return withFleetDefaults(defaults, ssh.user(), ssh.port(), ALL_SUPPORTED_PLUGINS, FLEET_COORDINATOR);
	}

	private static HostSettings readProfile(Properties p, String prefix, HostSettings defaults) {
		String remoteDirectory = migrateRemoteDirectory(
				p.getProperty(prefix + "remote-directory", defaults.remoteDirectory()));
		String sandboxRoot = migrateSandboxRoot(p.getProperty(prefix + "sandbox-root", defaults.sandboxRoot()));
		return new HostSettings(Integer.parseInt(p.getProperty(prefix + "port", Integer.toString(defaults.port()))),
				p.getProperty(prefix + "token", defaults.token()),
				Integer.parseInt(p.getProperty(prefix + "count", Integer.toString(defaults.count()))),
				AgentClient.LaunchMode.valueOf(p.getProperty(prefix + "launch-mode", defaults.launchMode().name())),
				p.getProperty(prefix + "capabilities", defaults.capabilities()),
				p.getProperty(prefix + "ssh-user", defaults.sshUser()),
				Integer.parseInt(p.getProperty(prefix + "ssh-port", Integer.toString(defaults.sshPort()))),
				p.getProperty(prefix + "identity-file", defaults.identityFile()),
				Boolean.parseBoolean(
						p.getProperty(prefix + "accept-new-host-key", Boolean.toString(defaults.acceptNewHostKey()))),
				p.getProperty(prefix + "coordinator", defaults.coordinator()), remoteDirectory,
				p.getProperty(prefix + "agent-jar", defaults.agentJar()),
				p.getProperty(prefix + "worker-jar", defaults.workerJar()), sandboxRoot,
				p.getProperty(prefix + "windows-sandbox-launcher", defaults.windowsSandboxLauncher()));
	}

	private static HostSettings migrateLegacyProfile(String host, HostSettings legacy) {
		SshDefaults ssh = KNOWN_HOSTS.get(host);
		return ssh == null
				? legacy
				: withFleetDefaults(legacy, ssh.user(), ssh.port(), ALL_SUPPORTED_PLUGINS, FLEET_COORDINATOR);
	}

	private static HostSettings migrateKnownCoordinator(String host, HostSettings profile, int version) {
		if (version >= CURRENT_VERSION || !KNOWN_HOSTS.containsKey(host)
				|| !defaults().coordinator().equals(profile.coordinator()))
			return profile;
		return withFleetDefaults(profile, profile.sshUser(), profile.sshPort(), profile.capabilities(),
				FLEET_COORDINATOR);
	}

	private static HostSettings withFleetDefaults(HostSettings profile, String sshUser, int sshPort,
			String capabilities, String coordinator) {
		return new HostSettings(profile.port(), profile.token(), profile.count(), profile.launchMode(), capabilities,
				sshUser, sshPort, profile.identityFile(), profile.acceptNewHostKey(), coordinator,
				profile.remoteDirectory(), profile.agentJar(), profile.workerJar(), profile.sandboxRoot(),
				profile.windowsSandboxLauncher());
	}

	private static String migrateRemoteDirectory(String value) {
		return "/opt/mechana/host-agent".equals(value) ? "~/.mechana/host-agent" : value;
	}

	private static String migrateSandboxRoot(String value) {
		return "/var/lib/mechana-sandbox".equals(value) ? "~/.mechana/sandbox" : value;
	}

	private static void writeProfile(Properties p, String prefix, HostSettings profile) {
		p.setProperty(prefix + "port", Integer.toString(profile.port()));
		p.setProperty(prefix + "token", profile.token());
		p.setProperty(prefix + "count", Integer.toString(profile.count()));
		p.setProperty(prefix + "launch-mode", profile.launchMode().name());
		p.setProperty(prefix + "capabilities", profile.capabilities());
		p.setProperty(prefix + "ssh-user", profile.sshUser());
		p.setProperty(prefix + "ssh-port", Integer.toString(profile.sshPort()));
		p.setProperty(prefix + "identity-file", profile.identityFile());
		p.setProperty(prefix + "accept-new-host-key", Boolean.toString(profile.acceptNewHostKey()));
		p.setProperty(prefix + "coordinator", profile.coordinator());
		p.setProperty(prefix + "remote-directory", profile.remoteDirectory());
		p.setProperty(prefix + "agent-jar", profile.agentJar());
		p.setProperty(prefix + "worker-jar", profile.workerJar());
		p.setProperty(prefix + "sandbox-root", profile.sandboxRoot());
		p.setProperty(prefix + "windows-sandbox-launcher", profile.windowsSandboxLauncher());
	}

	private record SshDefaults(String user, int port) {
	}
}
