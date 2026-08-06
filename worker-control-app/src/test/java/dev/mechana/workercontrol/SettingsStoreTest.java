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

	private static SettingsStore.HostSettings profile(int port, int sshPort, String remoteDirectory,
			String sandboxRoot) {
		return new SettingsStore.HostSettings(port, "token-" + port, 4, AgentClient.LaunchMode.SANDBOXED,
				"sleep,fractal-render", "root", sshPort, "/keys/id", true, "http://coordinator:8787", remoteDirectory,
				"/local/agent.jar", "/local/worker.jar", sandboxRoot);
	}
}
