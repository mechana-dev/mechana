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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.mp4parser.muxer.Sample;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.container.mp4.MovieCreator;

/** Converts user-facing dry audio into a worker-ready PCM WAV. */
public final class DryAudioImporter {
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("wav", "wave", "m4a", "aac", "mp4", "aif", "aiff");

	private DryAudioImporter() {
	}

	public static boolean supports(Path source) {
		return SUPPORTED_EXTENSIONS.contains(extension(source));
	}

	/**
	 * Returns {@code source} when no conversion is needed, otherwise
	 * {@code output}.
	 */
	public static Path prepare(Path source, int targetSampleRate, Path output) throws IOException {
		if (!Files.isRegularFile(source) || !supports(source))
			throw new IOException("Unsupported dry audio file: " + source);
		if (targetSampleRate < 1)
			throw new IllegalArgumentException("Target sample rate must be positive");
		if ("wav".equals(extension(source)) || "wave".equals(extension(source))) {
			try (WavFile.Reader input = WavFile.open(source)) {
				if (input.format().sampleRate() == targetSampleRate)
					return source;
			}
		}
		try {
			convertWithJavaSound(source, targetSampleRate, output);
			return output;
		} catch (IOException | UnsupportedAudioFileException | IllegalArgumentException unsupported) {
			if ("mp4".equals(extension(source))) {
				try {
					convertAacMp4(source, targetSampleRate, output);
					return output;
				} catch (IOException | RuntimeException fallbackFailure) {
					fallbackFailure.addSuppressed(unsupported);
					throw new IOException("Could not decode MP4 audio " + source.getFileName(), fallbackFailure);
				}
			}
			throw new IOException("Could not decode dry audio " + source.getFileName()
					+ "; use WAV, M4A/AAC/ALAC, MP4 with AAC audio, or AIFF without DRM", unsupported);
		}
	}

	private static void convertAacMp4(Path source, int targetSampleRate, Path output) throws IOException {
		Path parent = Objects.requireNonNull(output.toAbsolutePath().normalize().getParent());
		Files.createDirectories(parent);
		Path raw = Files.createTempFile(parent, ".decoded-mp4-", ".pcm");
		Path decoded = Files.createTempFile(parent, ".decoded-mp4-", ".wav");
		try (SeekableByteChannel channel = NIOUtils.readableChannel(source.toFile());
				OutputStream sink = Files.newOutputStream(raw)) {
			MP4Demuxer demuxer = MP4Demuxer.createRawMP4Demuxer(channel);
			if (demuxer.getAudioTracks().isEmpty())
				throw new IOException("MP4 contains no audio track");
			DemuxerTrack track = demuxer.getAudioTracks().getFirst();
			ByteBuffer privateData = track.getMeta().getCodecPrivate();
			if (privateData == null)
				throw new IOException("MP4 audio track is not AAC");
			Track audio = MovieCreator.build(source.toString()).getTracks().stream()
					.filter(candidate -> "soun".equals(candidate.getHandler())).findFirst()
					.orElseThrow(() -> new IOException("MP4 contains no readable audio track"));
			AudioFormat format = decodeAacPackets(audio, privateData, sink);
			sink.close();
			writeDecodedWav(raw, format, decoded);
			resample(decoded, targetSampleRate, output);
		} finally {
			Files.deleteIfExists(raw);
			Files.deleteIfExists(decoded);
		}
	}

	private static AudioFormat decodeAacPackets(Track track, ByteBuffer privateData, OutputStream sink)
			throws IOException {
		try {
			Class<?> decoderType = Class.forName("net.sourceforge.jaad.aac.Decoder");
			Class<?> receiverType = Class.forName("net.sourceforge.jaad.aac.Receiver");
			Class<?> bufferType = Class.forName("net.sourceforge.jaad.SampleBuffer");
			Object decoder = decoderType.getMethod("create", byte[].class).invoke(null,
					(Object) NIOUtils.toArray(privateData));
			Object samples = bufferType.getConstructor().newInstance();
			boolean decoded = false;
			for (Sample packet : track.getSamples()) {
				decoderType.getMethod("decodeFrame", byte[].class, receiverType).invoke(decoder,
						NIOUtils.toArray(packet.asByteBuffer()), samples);
				bufferType.getMethod("setBigEndian", boolean.class).invoke(samples, false);
				sink.write((byte[]) bufferType.getMethod("getData").invoke(samples));
				decoded = true;
			}
			if (!decoded)
				throw new IOException("MP4 audio track contains no decodable frames");
			int rate = (int) bufferType.getMethod("getSampleRate").invoke(samples);
			int bits = (int) bufferType.getMethod("getBitsPerSample").invoke(samples);
			int channels = (int) bufferType.getMethod("getChannels").invoke(samples);
			return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels, channels * bits / 8, rate,
					false);
		} catch (ReflectiveOperationException reflectionFailure) {
			throw new IOException("Pure-Java AAC decoder is unavailable", reflectionFailure);
		}
	}

	private static void convertWithJavaSound(Path source, int targetSampleRate, Path output)
			throws IOException, UnsupportedAudioFileException {
		Path parent = Objects.requireNonNull(output.toAbsolutePath().normalize().getParent());
		Files.createDirectories(parent);
		Path decoded = Files.createTempFile(parent, ".decoded-dry-", ".wav");
		Path raw = Files.createTempFile(parent, ".decoded-dry-", ".pcm");
		try (AudioInputStream input = AudioSystem.getAudioInputStream(source.toFile())) {
			if (!AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, input.getFormat()))
				throw new IllegalArgumentException("JDK audio conversion is unavailable");
			try (AudioInputStream pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, input)) {
				AudioFormat pcmFormat = pcm.getFormat();
				AudioFormat littleEndian = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, pcmFormat.getSampleRate(),
						16, pcmFormat.getChannels(), pcmFormat.getChannels() * 2, pcmFormat.getSampleRate(), false);
				try (AudioInputStream converted = AudioSystem.getAudioInputStream(littleEndian, pcm);
						OutputStream sink = Files.newOutputStream(raw)) {
					converted.transferTo(sink);
				}
				writeDecodedWav(raw, littleEndian, decoded);
			}
			resample(decoded, targetSampleRate, output);
		} finally {
			Files.deleteIfExists(raw);
			Files.deleteIfExists(decoded);
		}
	}

	private static void writeDecodedWav(Path raw, AudioFormat format, Path decoded) throws IOException {
		long frames = Files.size(raw) / format.getFrameSize();
		try (InputStream input = Files.newInputStream(raw);
				WavFile.Writer writer = WavFile.create24Bit(decoded, Math.round(format.getSampleRate()),
						format.getChannels(), frames)) {
			for (long frame = 0; frame < frames; frame++) {
				double[] values = new double[format.getChannels()];
				for (int channel = 0; channel < values.length; channel++) {
					int low = input.read();
					int high = input.read();
					if (high < 0)
						throw new IOException("Dry audio ended unexpectedly while decoding");
					values[channel] = (short) (low | high << 8) / 32768.0;
				}
				writer.writeFrame(values);
			}
		}
	}

	private static void resample(Path decoded, int targetSampleRate, Path output) throws IOException {
		try (WavFile.Reader reader = WavFile.open(decoded)) {
			WavFile.Format format = reader.format();
			if (format.sampleRate() == targetSampleRate) {
				copyAs24Bit(reader, format, output);
				return;
			}
			if (format.frames() > Integer.MAX_VALUE)
				throw new IOException("Dry audio is too long to resample");
			int sourceFrames = (int) format.frames();
			double[][] source = new double[format.channels()][sourceFrames];
			if (reader.read(source, 0, sourceFrames) != sourceFrames)
				throw new IOException("Dry audio ended unexpectedly while decoding");
			long outputFrames = Math.round(sourceFrames * (double) targetSampleRate / format.sampleRate());
			double cutoff = Math.min(1.0, targetSampleRate / (double) format.sampleRate());
			try (WavFile.Writer writer = WavFile.create24Bit(output, targetSampleRate, format.channels(),
					outputFrames)) {
				for (long frame = 0; frame < outputFrames; frame++) {
					double position = frame * (double) format.sampleRate() / targetSampleRate;
					double[] values = new double[format.channels()];
					for (int channel = 0; channel < values.length; channel++)
						values[channel] = windowedSinc(source[channel], position, cutoff);
					writer.writeFrame(values);
				}
			}
		}
	}

	private static void copyAs24Bit(WavFile.Reader reader, WavFile.Format format, Path output) throws IOException {
		int blockFrames = 4096;
		double[][] block = new double[format.channels()][blockFrames];
		try (WavFile.Writer writer = WavFile.create24Bit(output, format.sampleRate(), format.channels(),
				format.frames())) {
			int read;
			while ((read = reader.read(block, 0, blockFrames)) > 0)
				for (int frame = 0; frame < read; frame++) {
					double[] values = new double[format.channels()];
					for (int channel = 0; channel < values.length; channel++)
						values[channel] = block[channel][frame];
					writer.writeFrame(values);
				}
		}
	}

	private static double windowedSinc(double[] samples, double position, double cutoff) {
		int center = (int) Math.floor(position);
		double sum = 0;
		double weight = 0;
		for (int tap = -15; tap <= 16; tap++) {
			int index = center + tap;
			if (index < 0 || index >= samples.length)
				continue;
			double distance = position - index;
			double filteredDistance = cutoff * distance;
			double sinc = filteredDistance == 0
					? cutoff
					: cutoff * Math.sin(Math.PI * filteredDistance) / (Math.PI * filteredDistance);
			double window = 0.5 + 0.5 * Math.cos(Math.PI * distance / 16);
			double coefficient = sinc * window;
			sum += samples[index] * coefficient;
			weight += coefficient;
		}
		return weight == 0 ? 0 : sum / weight;
	}

	private static String extension(Path source) {
		Path name = source == null ? null : source.getFileName();
		if (name == null)
			return "";
		String value = name.toString();
		int separator = value.lastIndexOf('.');
		return separator < 0 ? "" : value.substring(separator + 1).toLowerCase(Locale.ROOT);
	}
}
