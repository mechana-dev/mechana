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

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Streaming RIFF/WAVE reader and 24-bit PCM writer. */
public final class WavFile {
	private WavFile() {
	}

	public record Format(int sampleRate, int channels, int bitsPerSample, boolean floatingPoint, long frames) {
	}

	public static Reader open(Path path) throws IOException {
		return new Reader(path);
	}

	public static Writer create24Bit(Path path, int sampleRate, int channels, long frames) throws IOException {
		return new Writer(path, sampleRate, channels, frames);
	}

	public static final class Reader implements Closeable {
		private final RandomAccessFile input;
		private final Format format;
		private final long dataEnd;

		private Reader(Path path) throws IOException {
			input = new RandomAccessFile(path.toFile(), "r");
			try {
				if (!"RIFF".equals(ascii(input, 4)))
					throw new IOException("WAV must use RIFF little-endian format");
				readUnsignedInt(input);
				if (!"WAVE".equals(ascii(input, 4)))
					throw new IOException("Not a WAVE file");
				int audioFormat = 0;
				int channels = 0;
				int sampleRate = 0;
				int bits = 0;
				long dataOffset = -1;
				long dataSize = -1;
				while (input.getFilePointer() + 8 <= input.length()) {
					String id = ascii(input, 4);
					long size = readUnsignedInt(input);
					long next = input.getFilePointer() + size + (size & 1);
					if ("fmt ".equals(id)) {
						audioFormat = readUnsignedShort(input);
						channels = readUnsignedShort(input);
						sampleRate = (int) readUnsignedInt(input);
						readUnsignedInt(input);
						readUnsignedShort(input);
						bits = readUnsignedShort(input);
					} else if ("data".equals(id)) {
						dataOffset = input.getFilePointer();
						dataSize = size;
					}
					input.seek(next);
				}
				boolean floating = audioFormat == 3;
				if (dataOffset < 0 || channels < 1 || sampleRate < 1
						|| !(audioFormat == 1 && (bits == 16 || bits == 24) || floating && bits == 32))
					throw new IOException("Supported WAV formats are 16/24-bit PCM and 32-bit IEEE float");
				int frameSize = Math.multiplyExact(channels, bits / 8);
				format = new Format(sampleRate, channels, bits, floating, dataSize / frameSize);
				dataEnd = dataOffset + dataSize;
				input.seek(dataOffset);
			} catch (IOException | RuntimeException failure) {
				input.close();
				throw failure;
			}
		}

		public Format format() {
			return format;
		}

		/** Reads interleaved frames into planar destination arrays. */
		public int read(double[][] destination, int offset, int requestedFrames) throws IOException {
			int available = (int) Math.min(requestedFrames,
					(dataEnd - input.getFilePointer()) / (format.channels() * format.bitsPerSample() / 8));
			for (int frame = 0; frame < available; frame++)
				for (int channel = 0; channel < format.channels(); channel++)
					destination[channel][offset + frame] = sample();
			return available;
		}

		private double sample() throws IOException {
			if (format.floatingPoint())
				return Float.intBitsToFloat(readInt(input));
			if (format.bitsPerSample() == 16)
				return (short) readUnsignedShort(input) / 32768.0;
			int value = input.readUnsignedByte() | input.readUnsignedByte() << 8 | input.readByte() << 16;
			return value / 8388608.0;
		}

		@Override
		public void close() throws IOException {
			input.close();
		}
	}

	public static final class Writer implements Closeable {
		private final DataOutputStream output;
		private final int channels;
		private final long frames;
		private long written;

		private Writer(Path path, int sampleRate, int channels, long frames) throws IOException {
			if (sampleRate < 1 || channels < 1 || frames < 0)
				throw new IllegalArgumentException("Invalid WAV output dimensions");
			long dataBytes = Math.multiplyExact(Math.multiplyExact(frames, channels), 3);
			if (dataBytes > 0xffff_ffffL - 36)
				throw new IOException("POC WAV output exceeds the RIFF 4 GiB limit");
			Files.createDirectories(java.util.Objects.requireNonNull(path.toAbsolutePath().normalize().getParent(),
					"WAV output path must have a parent directory"));
			output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
			this.channels = channels;
			this.frames = frames;
			writeAscii(output, "RIFF");
			writeInt(output, (int) (36 + dataBytes));
			writeAscii(output, "WAVEfmt ");
			writeInt(output, 16);
			writeShort(output, 1);
			writeShort(output, channels);
			writeInt(output, sampleRate);
			writeInt(output, Math.multiplyExact(sampleRate, channels * 3));
			writeShort(output, channels * 3);
			writeShort(output, 24);
			writeAscii(output, "data");
			writeInt(output, (int) dataBytes);
		}

		public void writeFrame(double[] samples) throws IOException {
			if (samples.length != channels || written >= frames)
				throw new IllegalStateException("Unexpected WAV output frame");
			for (double sample : samples) {
				int value = sample <= -1 ? -8388608 : sample >= 1 ? 8388607 : (int) Math.round(sample * 8388608.0);
				output.writeByte(value);
				output.writeByte(value >>> 8);
				output.writeByte(value >>> 16);
			}
			written++;
		}

		@Override
		public void close() throws IOException {
			try {
				if (written != frames)
					throw new EOFException("Expected " + frames + " output frames but wrote " + written);
			} finally {
				output.close();
			}
		}
	}

	private static String ascii(RandomAccessFile input, int length) throws IOException {
		byte[] value = new byte[length];
		input.readFully(value);
		return new String(value, StandardCharsets.US_ASCII);
	}

	private static int readUnsignedShort(RandomAccessFile input) throws IOException {
		return input.readUnsignedByte() | input.readUnsignedByte() << 8;
	}

	private static int readInt(RandomAccessFile input) throws IOException {
		return input.readUnsignedByte() | input.readUnsignedByte() << 8 | input.readUnsignedByte() << 16
				| input.readUnsignedByte() << 24;
	}

	private static long readUnsignedInt(RandomAccessFile input) throws IOException {
		return Integer.toUnsignedLong(readInt(input));
	}

	private static void writeAscii(DataOutputStream output, String value) throws IOException {
		output.write(value.getBytes(StandardCharsets.US_ASCII));
	}

	private static void writeShort(DataOutputStream output, int value) throws IOException {
		output.writeByte(value);
		output.writeByte(value >>> 8);
	}

	private static void writeInt(DataOutputStream output, int value) throws IOException {
		output.writeByte(value);
		output.writeByte(value >>> 8);
		output.writeByte(value >>> 16);
		output.writeByte(value >>> 24);
	}
}
