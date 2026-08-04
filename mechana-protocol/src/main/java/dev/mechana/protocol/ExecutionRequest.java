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

import dev.mechana.api.JobId;
import java.util.Objects;

/** Transport-neutral request to execute a registered task. */
public record ExecutionRequest(JobId jobId, String taskType, byte[] payload) {

	public ExecutionRequest {
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(taskType, "taskType");
		Objects.requireNonNull(payload, "payload");
		if (taskType.isBlank()) {
			throw new IllegalArgumentException("taskType must not be blank");
		}
		payload = payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
