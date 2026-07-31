package dev.mechana.api;

import java.util.Objects;

/** Identity and compatibility information for an executable plugin. */
public record PluginDescriptor(String id, String version) {

	public PluginDescriptor {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(version, "version");
		if (id.isBlank() || version.isBlank()) {
			throw new IllegalArgumentException("Plugin id and version must not be blank");
		}
	}
}
