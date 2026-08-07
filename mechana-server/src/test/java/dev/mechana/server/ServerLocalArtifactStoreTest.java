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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.api.ArtifactReference;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerLocalArtifactStoreTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void storesAndReadsContentWithVerifiedIdentity() throws Exception {
		ServerLocalArtifactStore store = new ServerLocalArtifactStore(temporaryDirectory);
		byte[] content = "mechana artifact".getBytes(StandardCharsets.UTF_8);
		ArtifactReference artifact = store.put("jobs/example/output.txt", new ByteArrayInputStream(content));

		assertEquals("server-local", artifact.providerId());
		assertEquals(content.length, artifact.sizeBytes());
		assertTrue(store.exists(artifact));
		assertEquals("mechana artifact", new String(store.open(artifact).readAllBytes(), StandardCharsets.UTF_8));

		store.delete(artifact);
		assertFalse(store.exists(artifact));
	}

	@Test
	void rejectsKeysThatEscapeTheProviderRoot() throws Exception {
		ServerLocalArtifactStore store = new ServerLocalArtifactStore(temporaryDirectory);
		assertThrows(IllegalArgumentException.class,
				() -> store.put("../outside.txt", new ByteArrayInputStream(new byte[] { 1 })));
	}
}
