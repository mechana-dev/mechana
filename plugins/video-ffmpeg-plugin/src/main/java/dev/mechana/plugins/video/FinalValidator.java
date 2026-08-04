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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FinalValidator {
	private final MediaProbe probe;
	public FinalValidator(MediaProbe probe) {
		this.probe = probe;
	}

	public VideoTypes.MediaInfo validate(Path output, VideoTypes.Plan plan) throws IOException, InterruptedException {
		VideoTypes.MediaInfo actual = probe.inspect(output, plan.options().processTimeout());
		String containerToken = plan.options().outputContainer() == VideoTypes.Container.MP4 ? "mp4" : "matroska";
		if (!actual.formatName().contains(containerToken))
			fail("Unexpected output container: " + actual.formatName());
		if (!"hevc".equals(actual.videoCodec()) || actual.videoStreams() != 1)
			fail("Output must contain exactly one HEVC video stream");
		if (actual.width() != plan.input().width() || actual.height() != plan.input().height())
			fail("Output dimensions changed");
		if (actual.audioStreams() != plan.input().audioStreams())
			fail("Output audio stream presence changed");
		double tolerance = Math.max(0.5, plan.input().durationSeconds() * 0.01);
		if (Math.abs(actual.durationSeconds() - plan.input().durationSeconds()) > tolerance)
			fail("Output duration outside tolerance");
		if (!Files.isRegularFile(output) || Files.size(output) == 0)
			fail("Output is empty");
		return actual;
	}

	public VideoTypes.MediaInfo validateSmallerThanInput(Path output, VideoTypes.Plan plan)
			throws IOException, InterruptedException {
		VideoTypes.MediaInfo actual = validate(output, plan);
		if (actual.inputBytes() >= plan.input().inputBytes())
			fail("Output is not smaller than input: " + actual.inputBytes() + " >= " + plan.input().inputBytes());
		return actual;
	}
	private static void fail(String message) throws IOException {
		throw new IOException("Final validation failed: " + message);
	}
}
