package dev.mechana.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mechana.api.JobId;
import dev.mechana.protocol.ExecutionRequest;
import org.junit.jupiter.api.Test;

class WorkerTest {

	@Test
	void recognizesSupportedTaskType() {
		Worker worker = new Worker("example");

		assertTrue(worker.supports(new ExecutionRequest(JobId.random(), "example", new byte[0])));
	}
}
