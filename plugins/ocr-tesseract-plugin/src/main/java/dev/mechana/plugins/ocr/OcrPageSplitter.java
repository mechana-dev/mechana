/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.mechana.plugins.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Rasterizes an ordered PDF page range into worker-local PNG inputs. */
public final class OcrPageSplitter {
	public Result split(Path source, Path output, int firstPage, int requestedPageCount, int dpi) throws IOException {
		Files.createDirectories(output);
		try (PDDocument document = Loader.loadPDF(source.toFile())) {
			int documentPages = document.getNumberOfPages();
			if (documentPages < 1 || firstPage > documentPages)
				throw new IllegalArgumentException("PDF contains no requested pages");
			int pageCount = requestedPageCount == 0
					? documentPages - firstPage + 1
					: Math.min(requestedPageCount, documentPages - firstPage + 1);
			PDFRenderer renderer = new PDFRenderer(document);
			List<Path> pages = new ArrayList<>(pageCount);
			for (int index = 0; index < pageCount; index++) {
				int documentPage = firstPage + index;
				Path page = output.resolve("page-%06d.png".formatted(documentPage));
				if (!ImageIO.write(renderer.renderImageWithDPI(documentPage - 1, dpi, ImageType.GRAY), "png",
						page.toFile()))
					throw new IOException("PNG writer is unavailable");
				pages.add(page);
			}
			return new Result(List.copyOf(pages), documentPages);
		}
	}

	public record Result(List<Path> pages, int documentPages) {
		public Result {
			pages = List.copyOf(pages);
		}
	}
}
