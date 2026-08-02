package dev.mechana.plugins.video;

@FunctionalInterface
public interface CancellationToken {
	boolean isCancelled();
	CancellationToken NEVER = () -> false;
}
