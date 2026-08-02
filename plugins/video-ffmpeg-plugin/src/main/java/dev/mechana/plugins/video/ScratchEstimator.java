package dev.mechana.plugins.video;

public final class ScratchEstimator {
	public long estimateBytes(VideoTypes.MediaInfo input) {
		// Source copy allowance + encoded segments + concatenated video + audio + final
		// + 25% uncertainty.
		return Math.max(256L * 1024 * 1024, Math.multiplyExact(input.inputBytes(), 6L));
	}
}
