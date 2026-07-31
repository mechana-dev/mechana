package dev.mechana.runtime;

import dev.mechana.coordinator.Coordinator;
import dev.mechana.worker.Worker;
import java.util.Objects;

/** Composition root for an embedded Mechana runtime. */
public record MechanaRuntime(Coordinator coordinator, Worker worker) {

	public MechanaRuntime {
		Objects.requireNonNull(coordinator, "coordinator");
		Objects.requireNonNull(worker, "worker");
	}
}
