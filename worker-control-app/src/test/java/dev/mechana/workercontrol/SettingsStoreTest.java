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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
	@TempDir
	Path temporary;

	@Test
	void roundTripsIndependentProfilesForEachHost() throws Exception {
		SettingsStore store = new SettingsStore(temporary.resolve("settings.properties"));
		SettingsStore.HostSettings alpha = profile(8790, 22, "/opt/alpha", "/var/lib/alpha");
		SettingsStore.HostSettings beta = profile(9890, 2222, "/srv/beta", "/scratch/beta");

		store.save(new SettingsStore.Settings(List.of("alpha", "beta"), "beta", Map.of("alpha", alpha, "beta", beta)));

		SettingsStore.Settings loaded = store.load();
		assertEquals("beta", loaded.lastHost());
		assertEquals(alpha, loaded.profiles().get("alpha"));
		assertEquals(beta, loaded.profiles().get("beta"));
	}

	@Test
	void migratesLegacyGlobalSettingsToTheLastSelectedHost() throws Exception {
		Path file = temporary.resolve("settings.properties");
		Files.writeString(file, "host.0=alpha\nlast-host=alpha\nport=9911\nssh-port=2201\n"
				+ "remote-directory=/legacy/agent\nsandbox-root=/legacy/sandbox\n");

		SettingsStore.Settings loaded = new SettingsStore(file).load();

		SettingsStore.HostSettings migrated = loaded.profiles().get("alpha");
		assertEquals(9911, migrated.port());
		assertEquals(2201, migrated.sshPort());
		assertEquals("/legacy/agent", migrated.remoteDirectory());
		assertEquals("/legacy/sandbox", migrated.sandboxRoot());
	}

	@Test
	void seedsKnownHostsWithTheirSshAndFullPluginDefaults() throws Exception {
		SettingsStore.Settings loaded = new SettingsStore(temporary.resolve("settings.properties")).load();

		assertKnownProfile(loaded, "marks-macbook-air-m4", "markvita", 22);
		assertKnownProfile(loaded, "rocinante", "markvita", 21012);
		assertKnownProfile(loaded, "srv959600", "root", 22);
		assertKnownProfile(loaded, "hyperion", "markf", 22);
	}

	@Test
	void migratesLegacyKnownHostToKnownDefaultsWhilePreservingOtherFields() throws Exception {
		Path file = temporary.resolve("settings.properties");
		Files.writeString(file, "host.0=rocinante\nlast-host=rocinante\nport=9911\ntoken=secret\ncount=7\n"
				+ "ssh-user=old-global-user\nssh-port=22\ncapabilities=fractal-render\ncoordinator=http\\://mba\\:8787\n");

		SettingsStore.HostSettings migrated = new SettingsStore(file).load().profiles().get("rocinante");

		assertEquals(9911, migrated.port());
		assertEquals("secret", migrated.token());
		assertEquals(7, migrated.count());
		assertEquals("http://mba:8787", migrated.coordinator());
		assertEquals("markvita", migrated.sshUser());
		assertEquals(21012, migrated.sshPort());
		assertEquals(SettingsStore.ALL_SUPPORTED_PLUGINS, migrated.capabilities());
	}

	@Test
	void preservesExplicitPerHostCustomizationsOnLaterLoads() throws Exception {
		Path file = temporary.resolve("settings.properties");
		Files.writeString(file, "settings-version=2\nhost.0=hyperion\nlast-host=hyperion\nprofile.0.port=8790\n"
				+ "profile.0.ssh-user=custom-user\nprofile.0.ssh-port=2222\nprofile.0.capabilities=sleep\n");

		SettingsStore.HostSettings loaded = new SettingsStore(file).load().profiles().get("hyperion");

		assertEquals("custom-user", loaded.sshUser());
		assertEquals(2222, loaded.sshPort());
		assertEquals("sleep", loaded.capabilities());
	}

	@Test
	void migratesOldLinuxSystemDefaultsToUserWritablePaths() throws Exception {
		Path file = temporary.resolve("settings.properties");
		Files.writeString(file,
				"host.0=mba\nlast-host=mba\nprofile.0.port=8790\n"
						+ "profile.0.remote-directory=/opt/mechana/host-agent\n"
						+ "profile.0.sandbox-root=/var/lib/mechana-sandbox\n");

		SettingsStore.HostSettings migrated = new SettingsStore(file).load().profiles().get("mba");

		assertEquals("~/.mechana/host-agent", migrated.remoteDirectory());
		assertEquals("~/.mechana/sandbox", migrated.sandboxRoot());
	}

	private static SettingsStore.HostSettings profile(int port, int sshPort, String remoteDirectory,
			String sandboxRoot) {
		return new SettingsStore.HostSettings(port, "token-" + port, 4, AgentClient.LaunchMode.SANDBOXED,
				"sleep,fractal-render", "root", sshPort, "/keys/id", true, "http://coordinator:8787", remoteDirectory,
				"/local/agent.jar", "/local/worker.jar", sandboxRoot, "/local/windows-sandbox.exe");
	}

	private static void assertKnownProfile(SettingsStore.Settings settings, String host, String user, int sshPort) {
		assertTrue(settings.hosts().contains(host));
		SettingsStore.HostSettings profile = settings.profiles().get(host);
		assertEquals(user, profile.sshUser());
		assertEquals(sshPort, profile.sshPort());
		assertEquals(SettingsStore.ALL_SUPPORTED_PLUGINS, profile.capabilities());
	}
}
