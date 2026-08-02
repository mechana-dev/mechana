package dev.mechana.plugins.video;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class VideoPluginDescriptor {
	public static final String ID = "dev.mechana.video-compress";
	public static final Set<String> INPUT_CONTAINERS = Set.of("mp4", "mov", "matroska", "webm");

	public void validate(Path input, Path output, VideoTypes.MediaInfo info, VideoTypes.Options options) {
		if (!java.nio.file.Files.isRegularFile(input))
			throw new IllegalArgumentException("Input is not a regular file: " + input);
		if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
			throw new IllegalArgumentException("Input and output must differ");
		boolean supportedInput = INPUT_CONTAINERS.stream().anyMatch(info.formatName()::contains);
		if (!supportedInput)
			throw new IllegalArgumentException("Input container must be MP4 or Matroska");
		String extension = extension(output);
		String expected = options.outputContainer() == VideoTypes.Container.MP4 ? "mp4" : "mkv";
		if (!expected.equals(extension))
			throw new IllegalArgumentException("Output extension must be ." + expected);
		if (info.videoStreams() != 1 || !"h264".equals(info.videoCodec()))
			throw new IllegalArgumentException("Exactly one H.264 video stream is required");
		if (info.audioStreams() > 1 || info.subtitleStreams() != 0 || info.chapterCount() != 0)
			throw new IllegalArgumentException("At most one audio stream and no subtitles or chapters are supported");
		if (info.durationSeconds() <= 0 || info.width() <= 0 || info.height() <= 0)
			throw new IllegalArgumentException("Input has invalid duration or dimensions");
	}

	private static String extension(Path path) {
		Path fileName = path.getFileName();
		if (fileName == null)
			return "";
		String name = fileName.toString().toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}
}
