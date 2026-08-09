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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerMainTest {
	@Test
	void restartCommandContainsOnlyServerConfiguration() throws Exception {
		List<String> command = ServerMain.restartCommand(8787, "http://mba.example:8787", Path.of("server-data"));

		assertTrue(command.contains("8787"));
		assertTrue(command.contains("http://mba.example:8787"));
		assertTrue(command.stream().anyMatch(value -> value.endsWith("server-data")));
		assertFalse(command.stream().anyMatch(value -> value.contains("plugin") && value.endsWith(".jar")));
		assertEquals(List.of("8787", "http://mba.example:8787", Path.of("server-data").toAbsolutePath().toString()),
				command.subList(command.size() - 3, command.size()));
	}

	@Test
	void packagedServerResolvesPluginsInsideItsAppBundle() {
		String originalAppPath = System.getProperty("jpackage.app-path");
		try {
			System.setProperty("jpackage.app-path",
					"/Applications/Mechana Server.app/Contents/MacOS/Mechana Server Daemon");
			ServerMain.PluginJars plugins = ServerMain.PluginJars.configured();

			assertEquals(Path.of("/Applications/Mechana Server.app/Contents/app/mechana-plugin-sleep.jar"),
					plugins.sleep());
			assertEquals(Path.of("/Applications/Mechana Server.app/Contents/app/mechana-plugin-blender-render.jar"),
					plugins.blender());
		} finally {
			if (originalAppPath == null) {
				System.clearProperty("jpackage.app-path");
			} else {
				System.setProperty("jpackage.app-path", originalAppPath);
			}
		}
	}

	@Test
	void launchdManagedServerReliesOnSupervisorForRestart() throws Exception {
		String original = System.getProperty("mechana.launchd.managed");
		try {
			System.setProperty("mechana.launchd.managed", "true");
			assertTrue(ServerMain.replacementCommand(8787, "http://localhost:8787", Path.of("data")).isEmpty());
		} finally {
			if (original == null)
				System.clearProperty("mechana.launchd.managed");
			else
				System.setProperty("mechana.launchd.managed", original);
		}
	}
}
