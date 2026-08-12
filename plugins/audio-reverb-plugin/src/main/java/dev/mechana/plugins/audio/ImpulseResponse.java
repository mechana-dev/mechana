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
package dev.mechana.plugins.audio;

import java.io.IOException;
import java.nio.file.Path;

/** Prepared in-memory IR samples; source audio remains streaming. */
public record ImpulseResponse(int sampleRate, double[][] channels) {
	public ImpulseResponse {
		channels = deepCopy(channels);
	}

	@Override
	public double[][] channels() {
		return deepCopy(channels);
	}

	public int channelCount() {
		return channels.length;
	}

	public double[] channel(int index) {
		return channels[index].clone();
	}

	public static ImpulseResponse read(Path path, boolean normalize) throws IOException {
		try (WavFile.Reader reader = WavFile.open(path)) {
			WavFile.Format format = reader.format();
			if (format.frames() > Integer.MAX_VALUE)
				throw new IOException("IR is too long for the POC in-memory preparation stage");
			double[][] samples = new double[format.channels()][(int) format.frames()];
			int offset = 0;
			while (offset < format.frames()) {
				int count = reader.read(samples, offset, (int) format.frames() - offset);
				if (count == 0)
					throw new IOException("Unexpected end of IR WAV");
				offset += count;
			}
			if (normalize) {
				double peak = 0;
				for (double[] channel : samples)
					for (double value : channel)
						peak = Math.max(peak, Math.abs(value));
				if (peak > 0)
					for (double[] channel : samples)
						for (int index = 0; index < channel.length; index++)
							channel[index] /= peak;
			}
			return new ImpulseResponse(format.sampleRate(), samples);
		}
	}

	public int length() {
		return channels[0].length;
	}

	private static double[][] deepCopy(double[][] source) {
		double[][] copy = source.clone();
		for (int index = 0; index < copy.length; index++)
			copy[index] = copy[index].clone();
		return copy;
	}
}
