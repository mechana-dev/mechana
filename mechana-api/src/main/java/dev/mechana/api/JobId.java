package dev.mechana.api;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier for a submitted job. */
public record JobId(UUID value) {

	public JobId {
		Objects.requireNonNull(value, "value");
	}

	/** Creates a new, random job identifier. */
	public static JobId random() {
		return new JobId(UUID.randomUUID());
	}
}
