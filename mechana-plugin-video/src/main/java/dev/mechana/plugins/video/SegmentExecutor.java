package dev.mechana.plugins.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class SegmentExecutor {
	private final FfmpegCommands commands;
	private final ExternalProcessRunner runner;
	public SegmentExecutor(FfmpegCommands commands, ExternalProcessRunner runner) {
		this.commands = commands;
		this.runner = runner;
	}

	public void execute(Path input, VideoTypes.Plan plan, CancellationToken cancellation,
			BiConsumer<Integer, String> progress) throws IOException, InterruptedException {
		Files.createDirectories(plan.scratchRoot().resolve("segments"));
		try (var pool = Executors.newFixedThreadPool(plan.options().parallelism())) {
			List<Callable<Void>> tasks = plan.segments().stream().<Callable<Void>>map(segment -> () -> {
				var result = runner.run(commands.segment(input, segment, plan.options()),
						plan.options().processTimeout(), cancellation, line -> {
							if (line.startsWith("out_time") || line.equals("progress=end"))
								progress.accept(segment.index(), line);
						});
				MediaProbe.requireSuccess(result, "segment " + segment.index());
				if (!Files.isRegularFile(segment.output()) || Files.size(segment.output()) == 0)
					throw new IOException("Segment produced no output: " + segment.index());
				return null;
			}).toList();
			try {
				for (var future : pool.invokeAll(tasks))
					future.get();
			} catch (ExecutionException failure) {
				Throwable cause = failure.getCause();
				if (cause instanceof IOException io)
					throw io;
				if (cause instanceof InterruptedException interrupted)
					throw interrupted;
				if (cause instanceof RuntimeException runtime)
					throw new IOException("Segment execution failed", runtime);
				throw new IOException("Segment execution failed", cause);
			}
		}
	}
}
