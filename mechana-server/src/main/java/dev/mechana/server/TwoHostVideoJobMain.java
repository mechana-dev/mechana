/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mechana.api.WorkUnit;
import dev.mechana.coordinator.InMemoryJobMonitor;
import dev.mechana.plugins.video.CancellationToken;
import dev.mechana.plugins.video.ExternalProcessRunner;
import dev.mechana.plugins.video.FfmpegCommands;
import dev.mechana.plugins.video.FinalValidator;
import dev.mechana.plugins.video.MediaProbe;
import dev.mechana.plugins.video.SegmentPlanner;
import dev.mechana.plugins.video.VideoAssembler;
import dev.mechana.plugins.video.VideoTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/** Monitored manual two-host proof; deliberately not scheduler integration. */
public final class TwoHostVideoJobMain {
	private static final String REMOTE_ROOT = "/tmp/mechana-two-host-monitored";
	private TwoHostVideoJobMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		if (args.length < 8) {
			System.err.println("Usage: TwoHostVideoJobMain <input> <output> <scratch> <remote-host> <remote-port> "
					+ "<local-address> <remote-address> <dashboard-port> [target-size-ratio]");
			System.exit(2);
		}
		Path input = Path.of(args[0]).toAbsolutePath();
		Path output = Path.of(args[1]).toAbsolutePath();
		Path scratch = Path.of(args[2]).toAbsolutePath();
		String remoteHost = args[3];
		int remotePort = Integer.parseInt(args[4]);
		String localAddress = args[5];
		String remoteAddress = args[6];
		int dashboardPort = Integer.parseInt(args[7]);
		double targetRatio = args.length > 8 ? Double.parseDouble(args[8]) : 0.65;
		if (targetRatio <= 0 || targetRatio >= 1)
			throw new IllegalArgumentException("Target size ratio must be between zero and one");

		InMemoryJobMonitor monitor = new InMemoryJobMonitor(UUID.randomUUID().toString(), "video-ffmpeg-two-host",
				Map.of("input", input.toString(), "output", output.toString()));
		JobDashboardServer dashboard = new JobDashboardServer(dashboardPort, monitor);
		dashboard.start();
		Runtime.getRuntime().addShutdownHook(new Thread(dashboard::close, "two-host-dashboard-shutdown"));
		System.out.printf("Live two-host dashboard: http://localhost:%d/%n", dashboard.port());
		Thread.ofVirtual().start(() -> {
			try {
				run(input, output, scratch, remoteHost, remotePort, localAddress, remoteAddress, targetRatio, monitor);
			} catch (Throwable failure) {
				monitor.fail(failure);
				failure.printStackTrace(System.err);
			}
		});
		new CountDownLatch(1).await();
	}

	private static void run(Path input, Path output, Path scratch, String remoteHost, int remotePort,
			String localAddress, String remoteAddress, double targetRatio, InMemoryJobMonitor monitor)
			throws IOException, InterruptedException {
		FfmpegCommands commands = new FfmpegCommands("ffmpeg", "ffprobe");
		ExternalProcessRunner runner = new ExternalProcessRunner();
		MediaProbe probe = new MediaProbe(commands, runner);
		monitor.onStage("PROBING");
		VideoTypes.MediaInfo inputInfo = probe.inspect(input, Duration.ofHours(1));
		VideoTypes.Options options = new VideoTypes.Options(VideoTypes.Container.MKV,
				VideoTypes.QualityMode.VISUALLY_LOSSLESS, 28, "slow",
				Duration.ofMillis(Math.round(inputInfo.durationSeconds() * 1000 / 8)), 8, Duration.ofHours(2));
		monitor.onStage("PLANNING");
		VideoTypes.Plan plan = new SegmentPlanner().plan(inputInfo, options,
				probe.keyframes(input, options.processTimeout()), scratch);
		if (plan.segments().size() != 8)
			throw new IOException("Expected exactly eight segments, planned " + plan.segments().size());
		monitor.onPlan(8,
				plan.segments().stream()
						.map(segment -> new WorkUnit(Integer.toString(segment.index()), "Segment " + segment.index(),
								segment.durationSeconds(),
								Map.of("range", "%.1f–%.1fs".formatted(segment.startSeconds(), segment.endSeconds()))))
						.toList());
		Files.createDirectories(scratch.resolve("segments"));
		long bitrate = targetVideoBitrate(inputInfo, targetRatio);
		persistPlan(plan, bitrate, targetRatio, localAddress, remoteAddress);
		prepareRemote(input, remoteHost, remotePort, runner, options.processTimeout());
		monitor.onStage("TRANSCODING");
		executeSegments(input, plan, commands, runner, remoteHost, remotePort, localAddress, remoteAddress, bitrate,
				monitor);
		monitor.onStage("ASSEMBLING");
		new VideoAssembler(commands, runner).assemble(input, output, plan, CancellationToken.NEVER);
		monitor.onStage("VALIDATING");
		new FinalValidator(probe).validateSmallerThanInput(output, plan);
		monitor.onStage("SUCCEEDED");
		cleanupRemote(remoteHost, remotePort, runner, options.processTimeout());
	}

	private static long targetVideoBitrate(VideoTypes.MediaInfo input, double ratio) {
		double targetTotalBitsPerSecond = input.inputBytes() * 8.0 * ratio / input.durationSeconds();
		long audioAndOverheadReserve = input.audioStreams() == 1 ? 512_000 : 64_000;
		return Math.max(250_000, Math.round(targetTotalBitsPerSecond - audioAndOverheadReserve));
	}

	private static void executeSegments(Path input, VideoTypes.Plan plan, FfmpegCommands commands,
			ExternalProcessRunner runner, String remoteHost, int remotePort, String localAddress, String remoteAddress,
			long bitrate, InMemoryJobMonitor monitor) throws IOException, InterruptedException {
		try (var pool = Executors.newFixedThreadPool(8)) {
			List<Callable<Void>> tasks = new ArrayList<>();
			for (VideoTypes.Segment segment : plan.segments()) {
				boolean local = segment.index() < 4;
				tasks.add(() -> {
					executeSegment(input, segment, plan, commands, runner, remoteHost, remotePort,
							local ? localAddress : remoteAddress, bitrate, local, monitor);
					return null;
				});
			}
			try {
				for (var future : pool.invokeAll(tasks))
					future.get();
			} catch (ExecutionException failure) {
				throw new IOException("Two-host segment execution failed", failure.getCause());
			}
		}
	}

	private static void executeSegment(Path input, VideoTypes.Segment segment, VideoTypes.Plan plan,
			FfmpegCommands commands, ExternalProcessRunner runner, String remoteHost, int remotePort,
			String workerAddress, long bitrate, boolean local, InMemoryJobMonitor monitor)
			throws IOException, InterruptedException {
		String workUnitId = Integer.toString(segment.index());
		monitor.onWorkUnitStarted(workUnitId, workerAddress);
		try {
			List<String> command = local
					? commands.bitrateSegment(input, segment, plan.options(), bitrate)
					: remoteCommand(segment, plan.options(), remoteHost, remotePort, bitrate);
			var result = runner.run(command, plan.options().processTimeout(), CancellationToken.NEVER, line -> {
				if (line.startsWith("out_time") || line.equals("progress=end"))
					monitor.onWorkUnitProgress(workUnitId, progressPercent(line, segment),
							Map.of("ffmpegProgress", line));
			});
			if (result.exitCode() != 0)
				throw new IOException("Segment " + segment.index() + " failed: " + result.stderr().strip());
			if (!local)
				copyRemoteSegment(segment, remoteHost, remotePort, runner, plan.options().processTimeout());
			if (!Files.isRegularFile(segment.output()) || Files.size(segment.output()) == 0)
				throw new IOException("Segment produced no output: " + segment.index());
			monitor.onWorkUnitCompleted(workUnitId);
		} catch (IOException | InterruptedException | RuntimeException failure) {
			monitor.onWorkUnitFailed(workUnitId, failure.getMessage());
			throw failure;
		}
	}

	private static int progressPercent(String update, VideoTypes.Segment segment) {
		if ("progress=end".equals(update))
			return 100;
		if (!update.startsWith("out_time_us="))
			return 0;
		try {
			double seconds = Long.parseLong(update.substring("out_time_us=".length())) / 1_000_000.0;
			return (int) Math.clamp(Math.round(seconds * 100.0 / segment.durationSeconds()), 0, 99);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static List<String> remoteCommand(VideoTypes.Segment segment, VideoTypes.Options options, String host,
			int port, long bitrate) {
		String output = REMOTE_ROOT + "/segments/segment-%05d.mkv".formatted(segment.index());
		String command = String.format(Locale.ROOT,
				"/usr/local/bin/ffmpeg -hide_banner -loglevel error -y -ss %.6f -i %s/input.mp4 -t %.6f "
						+ "-map 0:v:0 -an -sn -c:v libx265 -preset %s -b:v %d -maxrate %d -bufsize %d "
						+ "-progress pipe:1 -nostats -f matroska %s",
				segment.startSeconds(), REMOTE_ROOT, segment.durationSeconds(), options.preset(), bitrate, bitrate,
				Math.multiplyExact(bitrate, 2), output);
		return List.of("ssh", "-p", Integer.toString(port), "-o", "BatchMode=yes", "markvita@" + host, command);
	}

	private static void prepareRemote(Path input, String host, int port, ExternalProcessRunner runner, Duration timeout)
			throws IOException, InterruptedException {
		requireSuccess(runner.run(
				List.of("ssh", "-p", Integer.toString(port), "-o", "BatchMode=yes", "markvita@" + host,
						"rm -rf " + REMOTE_ROOT + " && mkdir -p " + REMOTE_ROOT + "/segments"),
				timeout, CancellationToken.NEVER, ignored -> {
				}), "remote scratch preparation");
		requireSuccess(runner.run(
				List.of("scp", "-P", Integer.toString(port), input.toString(),
						"markvita@" + host + ":" + REMOTE_ROOT + "/input.mp4"),
				timeout, CancellationToken.NEVER, ignored -> {
				}), "remote input transfer");
	}

	private static void copyRemoteSegment(VideoTypes.Segment segment, String host, int port,
			ExternalProcessRunner runner, Duration timeout) throws IOException, InterruptedException {
		String remote = "markvita@" + host + ":" + REMOTE_ROOT
				+ "/segments/segment-%05d.mkv".formatted(segment.index());
		requireSuccess(runner.run(List.of("scp", "-P", Integer.toString(port), remote, segment.output().toString()),
				timeout, CancellationToken.NEVER, ignored -> {
				}), "remote segment transfer");
	}

	private static void cleanupRemote(String host, int port, ExternalProcessRunner runner, Duration timeout)
			throws IOException, InterruptedException {
		requireSuccess(runner.run(List.of("ssh", "-p", Integer.toString(port), "-o", "BatchMode=yes",
				"markvita@" + host, "rm -rf " + REMOTE_ROOT), timeout, CancellationToken.NEVER, ignored -> {
				}), "remote scratch cleanup");
	}

	private static void requireSuccess(ExternalProcessRunner.Result result, String operation) throws IOException {
		if (result.exitCode() != 0)
			throw new IOException(operation + " failed: " + result.stderr().strip());
	}

	private static void persistPlan(VideoTypes.Plan plan, long bitrate, double ratio, String localAddress,
			String remoteAddress) throws IOException {
		var document = new LinkedHashMap<String, Object>();
		document.put("targetSizeRatio", ratio);
		document.put("videoBitrate", bitrate);
		document.put("segments", plan.segments().stream().map(segment -> {
			var item = new LinkedHashMap<String, Object>();
			item.put("index", segment.index());
			item.put("startSeconds", segment.startSeconds());
			item.put("endSeconds", segment.endSeconds());
			item.put("workerAddress", segment.index() < 4 ? localAddress : remoteAddress);
			return item;
		}).toList());
		Files.createDirectories(plan.scratchRoot());
		new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
				.writeValue(plan.scratchRoot().resolve("plan.json").toFile(), document);
	}
}
