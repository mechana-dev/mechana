package dev.mechana.worker;

import dev.mechana.protocol.ExecutionRequest;
import java.util.Objects;

/**
 * Initial worker boundary for determining whether a task can be handled
 * locally.
 */
public final class Worker {

	private final String taskType;

	public Worker(String taskType) {
		this.taskType = Objects.requireNonNull(taskType, "taskType");
	}

	public boolean supports(ExecutionRequest request) {
		return taskType.equals(Objects.requireNonNull(request, "request").taskType());
	}
}
