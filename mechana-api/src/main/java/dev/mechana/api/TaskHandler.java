package dev.mechana.api;

/**
 * Extension point implemented by executable task plugins.
 *
 * @param <I>
 *            input type
 * @param <O>
 *            output type
 */
@FunctionalInterface
public interface TaskHandler<I, O> {

	O execute(I input);
}
