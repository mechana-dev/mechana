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
}
