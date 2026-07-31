package dev.mechana.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.mechana.coordinator.Coordinator;
import dev.mechana.worker.Worker;
import org.junit.jupiter.api.Test;

class MechanaRuntimeTest {

	@Test
	void composesCoordinatorAndWorker() {
		MechanaRuntime runtime = new MechanaRuntime(new Coordinator(), new Worker("example"));

		assertNotNull(runtime.coordinator());
		assertNotNull(runtime.worker());
	}
}
