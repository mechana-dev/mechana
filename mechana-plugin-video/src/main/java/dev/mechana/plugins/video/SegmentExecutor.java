package dev.mechana.plugins.video;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
	private final String workerAddress;
	public SegmentExecutor(FfmpegCommands commands, ExternalProcessRunner runner) {
		this(commands, runner, resolveWorkerAddress());
	}

	public SegmentExecutor(FfmpegCommands commands, ExternalProcessRunner runner, String workerAddress) {
		this.commands = commands;
		this.runner = runner;
		this.workerAddress = workerAddress;
	}

	public void execute(Path input, VideoTypes.Plan plan, CancellationToken cancellation,
			BiConsumer<Integer, String> progress) throws IOException, InterruptedException {
		execute(input, plan, cancellation, new VideoJobObserver() {
			@Override
			public void onSegmentProgress(int segment, String update) {
				progress.accept(segment, update);
			}
		});
	}

	public void execute(Path input, VideoTypes.Plan plan, CancellationToken cancellation, VideoJobObserver observer)
			throws IOException, InterruptedException {
		Files.createDirectories(plan.scratchRoot().resolve("segments"));
		try (var pool = Executors.newFixedThreadPool(plan.options().parallelism())) {
			List<Callable<Void>> tasks = plan.segments().stream().<Callable<Void>>map(segment -> () -> {
				observer.onSegmentStarted(segment.index(), workerAddress);
				try {
					var result = runner.run(commands.segment(input, segment, plan.options()),
							plan.options().processTimeout(), cancellation, line -> {
								if (line.startsWith("out_time") || line.equals("progress=end"))
									observer.onSegmentProgress(segment.index(), line);
							});
					MediaProbe.requireSuccess(result, "segment " + segment.index());
					if (!Files.isRegularFile(segment.output()) || Files.size(segment.output()) == 0)
						throw new IOException("Segment produced no output: " + segment.index());
					observer.onSegmentCompleted(segment.index());
					return null;
				} catch (IOException | InterruptedException | RuntimeException failure) {
					observer.onSegmentFailed(segment.index(), failure.getMessage());
					throw failure;
				}
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

	private static String resolveWorkerAddress() {
		String configured = System.getenv("MECHANA_WORKER_ADDRESS");
		if (configured != null && !configured.isBlank())
			return configured;
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException unavailable) {
			return "unknown";
		}
	}
}
