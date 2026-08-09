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
package dev.mechana.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mechana.protocol.Messages.BlenderJobSubmitRequest;
import dev.mechana.protocol.Messages.ClientVideoChunk;
import dev.mechana.protocol.Messages.JobSubmitRequest;
import dev.mechana.protocol.Messages.VideoJobSubmitRequest;
import org.junit.jupiter.api.Test;

class MessagesTest {
	@Test
	void acceptsZeroAsFleetDerivedTaskCount() {
		assertDoesNotThrow(() -> new JobSubmitRequest(0, 1000));
		assertDoesNotThrow(() -> new VideoJobSubmitRequest("input.mp4", 10, 0, 0.75));
		assertDoesNotThrow(() -> new BlenderJobSubmitRequest("scene.blend", 1, 48, 0, 640, 360, 32, 24));
	}

	@Test
	void rejectsNegativeTaskCounts() {
		assertThrows(IllegalArgumentException.class, () -> new JobSubmitRequest(-1, 1000));
		assertThrows(IllegalArgumentException.class, () -> new VideoJobSubmitRequest("input.mp4", 10, -1, 0.75));
		assertThrows(IllegalArgumentException.class,
				() -> new BlenderJobSubmitRequest("scene.blend", 1, 48, -1, 640, 360, 32, 24));
	}

	@Test
	void videoStorageDefaultsToServerLocalAndClientLocalRequiresAnUpload() {
		VideoJobSubmitRequest compatible = new VideoJobSubmitRequest("input.mp4", 10, 2, 0.75);
		assertEquals("server-local", compatible.storageProvider());
		assertEquals("", compatible.sourceUploadToken());
		assertThrows(IllegalArgumentException.class,
				() -> new VideoJobSubmitRequest("input.mp4", 10, 2, 0.75, "client-local", ""));
		assertDoesNotThrow(() -> new VideoJobSubmitRequest("input.mp4", 10, 2, 0.75, "client-local", "upload-token"));
	}

	@Test
	void acceptsCompleteDirectClientVideoMetadata() {
		var chunks = java.util.List.of(new ClientVideoChunk("http://client:49000/client-video/token/chunks/0", 0, 5,
				100, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
		assertDoesNotThrow(() -> new VideoJobSubmitRequest("input.mp4", 5, 1, 0.75, "client-local", "", chunks,
				"http://client:49000/client-video/token/outputs/{index}", 1_000_000));
	}
}
