package dev.mechana.api;

import java.util.Map;

/**
 * Plugin-supplied display description and progress weight for one work unit.
 */
public record WorkUnit(String id, String label, double weight, Map<String, String> details) {
	public WorkUnit {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("Work-unit ID is required");
		if (label == null || label.isBlank())
			throw new IllegalArgumentException("Work-unit label is required");
		if (!Double.isFinite(weight) || weight <= 0)
			throw new IllegalArgumentException("Work-unit weight must be positive and finite");
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
