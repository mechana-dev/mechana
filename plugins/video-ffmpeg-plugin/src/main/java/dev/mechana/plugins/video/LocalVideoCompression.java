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

package dev.mechana.plugins.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mechana.api.JobObserver;
import dev.mechana.api.WorkUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class LocalVideoCompression {
	private final FfmpegCommands commands;
	private final ExternalProcessRunner runner;
	public LocalVideoCompression(FfmpegCommands commands) {
		this.commands = commands;
		this.runner = new ExternalProcessRunner();
	}

	public VideoTypes.MediaInfo run(Path input, Path output, Path scratch, VideoTypes.Options options,
			CancellationToken cancellation, BiConsumer<Integer, String> progress)
			throws IOException, InterruptedException {
		return run(input, output, scratch, options, cancellation, new JobObserver() {
			@Override
			public void onWorkUnitProgress(String workUnitId, int percent, Map<String, String> details) {
				progress.accept(Integer.parseInt(workUnitId), details.getOrDefault("ffmpegProgress", percent + "%"));
			}
		});
	}

	public VideoTypes.MediaInfo run(Path input, Path output, Path scratch, VideoTypes.Options options,
			CancellationToken cancellation, JobObserver observer) throws IOException, InterruptedException {
		observer.onStage("PROBING");
		RuntimeProbe runtimeProbe = new RuntimeProbe(commands, runner);
		var capabilities = runtimeProbe.inspect();
		if (!capabilities.usable())
			throw new IOException("FFmpeg runtime is unavailable or lacks libx265: " + capabilities);
		Files.createDirectories(scratch);
		MediaProbe probe = new MediaProbe(commands, runner);
		VideoTypes.MediaInfo inputInfo = probe.inspect(input, options.processTimeout());
		new VideoPluginDescriptor().validate(input, output, inputInfo, options);
		observer.onStage("PLANNING");
		VideoTypes.Plan plan = new SegmentPlanner().plan(inputInfo, options,
				probe.keyframes(input, options.processTimeout()), scratch);
		observer.onPlan(plan.options().parallelism(),
				plan.segments().stream()
						.map(segment -> new WorkUnit(Integer.toString(segment.index()), "Segment " + segment.index(),
								segment.durationSeconds(),
								Map.of("range", "%.1f–%.1fs".formatted(segment.startSeconds(), segment.endSeconds()))))
						.toList());
		long usable = Files.getFileStore(scratch).getUsableSpace();
		long required = new ScratchEstimator().estimateBytes(inputInfo);
		if (usable < required)
			throw new IOException("Insufficient scratch: need " + required + " bytes, available " + usable);
		persistPlan(plan);
		observer.onStage("TRANSCODING");
		new SegmentExecutor(commands, runner).execute(input, plan, cancellation, observer);
		observer.onStage("ASSEMBLING");
		new VideoAssembler(commands, runner).assemble(input, output, plan, cancellation);
		observer.onStage("VALIDATING");
		VideoTypes.MediaInfo result = new FinalValidator(probe, commands, runner).validate(output, plan);
		observer.onStage("SUCCEEDED");
		return result;
	}

	private static void persistPlan(VideoTypes.Plan plan) throws IOException {
		var document = new LinkedHashMap<String, Object>();
		document.put("inputDurationSeconds", plan.input().durationSeconds());
		document.put("qualityMode", plan.options().qualityMode().name());
		document.put("crf", plan.options().crf());
		document.put("preset", plan.options().preset());
		document.put("targetSegmentDurationMillis", plan.options().targetSegmentDuration().toMillis());
		document.put("segments", plan.segments().stream().map(segment -> {
			var item = new LinkedHashMap<String, Object>();
			item.put("index", segment.index());
			item.put("startSeconds", segment.startSeconds());
			item.put("endSeconds", segment.endSeconds());
			item.put("output", segment.output().toString());
			return item;
		}).toList());
		new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
				.writeValue(plan.scratchRoot().resolve("plan.json").toFile(), document);
	}
}
