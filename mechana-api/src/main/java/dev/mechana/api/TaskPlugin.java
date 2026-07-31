package dev.mechana.api;

/** A dynamically downloadable unit of work executed by a Mechana worker. */
public interface TaskPlugin {

	PluginDescriptor descriptor();

	void execute(TaskContext context) throws PluginExecutionException;
}
