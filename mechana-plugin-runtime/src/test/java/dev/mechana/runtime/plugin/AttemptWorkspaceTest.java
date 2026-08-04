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
package dev.mechana.runtime.plugin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttemptWorkspaceTest {
	@TempDir
	Path temporary;
	@Test
	void createsFixedAttemptLayoutAndRejectsTraversal() throws Exception {
		AttemptWorkspace workspace = AttemptWorkspace.create(temporary, "job-1", "attempt-2");
		assertAll(() -> assertTrue(workspace.input().toFile().isDirectory()),
				() -> assertTrue(workspace.work().toFile().isDirectory()),
				() -> assertTrue(workspace.output().toFile().isDirectory()),
				() -> assertTrue(workspace.logs().toFile().isDirectory()),
				() -> assertThrows(IllegalArgumentException.class,
						() -> AttemptWorkspace.create(temporary, "../escape", "attempt")));
	}
}
