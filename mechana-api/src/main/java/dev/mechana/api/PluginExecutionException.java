package dev.mechana.api;

/** Indicates that a plugin could not finish its assigned task. */
public final class PluginExecutionException extends Exception {

	public PluginExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
