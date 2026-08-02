package dev.mechana.client;

import dev.mechana.protocol.Messages.OcrJobSubmitRequest;
import java.io.IOException;
import java.net.URI;

/** Submits a server-rasterized distributed OCR job. */
public final class OcrClientMain {
	private OcrClientMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		URI server = URI.create(args.length > 0 ? args[0] : "http://localhost:8787");
		if (args.length < 2)
			throw new IllegalArgumentException(
					"Usage: OcrClientMain [server] <pdf> [tasks] [dpi] [language] [title] [first-page] [page-count]");
		String source = args[1];
		int tasks = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		int dpi = args.length > 3 ? Integer.parseInt(args[3]) : 300;
		String language = args.length > 4 ? args[4] : "eng";
		String title = args.length > 5 ? args[5] : "OCR Document";
		int firstPage = args.length > 6 ? Integer.parseInt(args[6]) : 1;
		int pageCount = args.length > 7 ? Integer.parseInt(args[7]) : 0;
		MechanaClient client = new MechanaClient(server);
		String jobId = client
				.submitOcr(new OcrJobSubmitRequest(source, tasks, dpi, language, title, firstPage, pageCount));
		System.out.printf("Submitted OCR job %s%n", jobId);
		System.out.printf("Loopback job dashboard: %s%n", client.dashboard(jobId));
	}
}
