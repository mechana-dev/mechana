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
