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

package dev.mechana.plugins.video;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalProcessRunnerTest {
	@TempDir
	Path temp;

	@Test
	void capturesOutputWithoutFfmpeg() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		var result = new ExternalProcessRunner().run(List.of(java, "-version"), Duration.ofSeconds(10),
				CancellationToken.NEVER, ignored -> {
				});
		assertEquals(0, result.exitCode());
		assertTrue(result.stderr().contains("version"));
	}

	@Test
	void enforcesTimeoutAndTerminatesChild() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Path source = temp.resolve("Sleeper.java");
		Files.writeString(source,
				"public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(30000); } }");
		assertThrows(ExternalProcessRunner.ProcessTimeoutException.class, () -> new ExternalProcessRunner()
				.run(List.of(java, source.toString()), Duration.ofMillis(100), CancellationToken.NEVER, ignored -> {
				}));
	}
}
