package dev.mechana.coordinator;

import dev.mechana.api.JobId;
import dev.mechana.protocol.ExecutionRequest;
import java.util.Objects;

/** Initial coordinator boundary for accepting work into the control plane. */
public final class Coordinator {

	public JobId accept(ExecutionRequest request) {
		return Objects.requireNonNull(request, "request").jobId();
	}
}
