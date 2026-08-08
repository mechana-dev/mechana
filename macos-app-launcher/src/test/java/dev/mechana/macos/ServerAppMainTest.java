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

package dev.mechana.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerAppMainTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void preservesDesktopLauncherDataWhenPresent() throws Exception {
		Path existing = temporaryDirectory.resolve("Projects/mechana/.mechana/server");
		Files.createDirectories(existing);
		assertEquals(existing, ServerAppMain.existingDataDirectory(temporaryDirectory));
	}

	@Test
	void defaultsToUserMechanaData() {
		assertEquals(temporaryDirectory.resolve(".mechana/server"),
				ServerAppMain.existingDataDirectory(temporaryDirectory));
	}

	@Test
	void launchAgentUsesBundledDaemonLauncher() {
		String plist = ServerAppMain.launchAgentPlist(Path.of("/Applications/Mechana & Server.app"),
				temporaryDirectory);
		assertTrue(plist.contains("Contents/MacOS/Mechana Server Daemon"));
		assertTrue(plist.contains("Mechana &amp; Server.app"));
	}
}
