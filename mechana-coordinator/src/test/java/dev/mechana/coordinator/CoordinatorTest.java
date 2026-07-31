package dev.mechana.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mechana.api.JobId;
import dev.mechana.protocol.ExecutionRequest;
import org.junit.jupiter.api.Test;

class CoordinatorTest {

	@Test
	void acceptsExecutionRequest() {
		JobId jobId = JobId.random();

		assertEquals(jobId, new Coordinator().accept(new ExecutionRequest(jobId, "example", new byte[0])));
	}
}
