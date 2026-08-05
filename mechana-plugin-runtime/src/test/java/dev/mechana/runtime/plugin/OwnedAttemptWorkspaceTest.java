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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedAttemptWorkspaceTest {
	@TempDir
	Path temporary;

	@Test
	void closeDeletesTheAttemptAndEmptyJobDirectory() throws Exception {
		Path root;
		try (OwnedAttemptWorkspace owned = OwnedAttemptWorkspace.create(temporary, "job", "attempt", "worker")) {
			root = owned.workspace().root();
			Files.writeString(owned.workspace().work().resolve("scratch"), "data");
			assertTrue(Files.exists(root.resolve(".owner")));
		}
		assertFalse(Files.exists(root));
		assertFalse(Files.exists(temporary.resolve("job")));
	}

	@Test
	void reclaimerSkipsLockedAttemptsAndDeletesThemAfterOwnershipEnds() throws Exception {
		OwnedAttemptWorkspace owned = OwnedAttemptWorkspace.create(temporary, "job", "attempt", "worker");
		Path attempt = owned.workspace().root();
		assertEquals(0, OwnedAttemptWorkspace.reclaimAbandoned(temporary));
		owned.close();

		AttemptWorkspace abandoned = AttemptWorkspace.create(temporary, "job", "abandoned");
		Path abandonedRoot = abandoned.root();
		Files.writeString(abandonedRoot.resolve(".owner"), "worker=dead\n");
		Files.createFile(abandonedRoot.resolve(".owner.lock"));
		assertEquals(1, OwnedAttemptWorkspace.reclaimAbandoned(temporary));
		assertFalse(Files.exists(abandonedRoot));
	}
}
