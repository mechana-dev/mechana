package dev.mechana.server;

import dev.mechana.plugins.video.CancellationToken;
import dev.mechana.plugins.video.FfmpegCommands;
import dev.mechana.plugins.video.LocalVideoCompression;
import dev.mechana.plugins.video.VideoTypes;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/** Runs one local video job with a live HTTP dashboard. */
public final class VideoJobMain {
	private VideoJobMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		if (args.length < 2) {
			System.err.println("Usage: VideoJobMain <input> <output> [scratch] [segment-seconds] [workers] [port]");
			System.exit(2);
		}
		Path input = Path.of(args[0]).toAbsolutePath();
		Path output = Path.of(args[1]).toAbsolutePath();
		Path scratch = args.length > 2
				? Path.of(args[2]).toAbsolutePath()
				: output.resolveSibling(output.getFileName() + ".scratch");
		VideoTypes.Container container = output.toString().toLowerCase(Locale.ROOT).endsWith(".mkv")
				? VideoTypes.Container.MKV
				: VideoTypes.Container.MP4;
		VideoTypes.Options defaults = VideoTypes.Options.defaults(container);
		VideoTypes.Options options = new VideoTypes.Options(container, defaults.qualityMode(), defaults.crf(),
				defaults.preset(),
				args.length > 3 ? Duration.ofSeconds(Long.parseLong(args[3])) : defaults.targetSegmentDuration(),
				args.length > 4 ? Integer.parseInt(args[4]) : defaults.parallelism(), defaults.processTimeout());
		int port = args.length > 5 ? Integer.parseInt(args[5]) : 8081;
		VideoJobMonitor monitor = new VideoJobMonitor(input, output);
		VideoJobDashboardServer dashboard = new VideoJobDashboardServer(port, monitor);
		dashboard.start();
		Runtime.getRuntime().addShutdownHook(new Thread(dashboard::close, "video-dashboard-shutdown"));
		System.out.printf("Live video job dashboard: http://localhost:%d/%n", dashboard.port());
		Thread.ofVirtual().start(() -> {
			try {
				String ffmpeg = System.getenv().getOrDefault("MECHANA_FFMPEG", "ffmpeg");
				String ffprobe = System.getenv().getOrDefault("MECHANA_FFPROBE", "ffprobe");
				new LocalVideoCompression(new FfmpegCommands(ffmpeg, ffprobe)).run(input, output, scratch, options,
						CancellationToken.NEVER, monitor);
			} catch (Throwable failure) {
				monitor.fail(failure);
				failure.printStackTrace(System.err);
			}
		});
		new CountDownLatch(1).await();
	}
}
