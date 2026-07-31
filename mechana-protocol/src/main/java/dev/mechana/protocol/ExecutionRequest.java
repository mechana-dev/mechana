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
