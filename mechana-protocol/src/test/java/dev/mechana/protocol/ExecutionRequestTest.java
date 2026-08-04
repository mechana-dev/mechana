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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.mechana.api.JobId;
import org.junit.jupiter.api.Test;

class ExecutionRequestTest {

	@Test
	void protectsPayloadFromMutation() {
		byte[] payload = {1, 2, 3};
		ExecutionRequest request = new ExecutionRequest(JobId.random(), "example", payload);

		payload[0] = 9;

		assertArrayEquals(new byte[]{1, 2, 3}, request.payload());
		assertNotSame(request.payload(), request.payload());
	}
}
