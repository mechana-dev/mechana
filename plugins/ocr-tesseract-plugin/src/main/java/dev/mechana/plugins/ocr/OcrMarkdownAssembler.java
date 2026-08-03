package dev.mechana.plugins.ocr;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates ordered OCR page outputs and assembles readable Markdown and LaTeX
 * documents.
 */
public final class OcrMarkdownAssembler {
	public Result assemble(List<Path> batches, Path outputDirectory, int pageCount, String title) throws IOException {
		return assemble(batches, outputDirectory, 1, pageCount, title);
	}

	public Result assemble(List<Path> batches, Path outputDirectory, int firstPage, int pageCount, String title)
			throws IOException {
		Path pages = outputDirectory.resolve("pages");
		Files.createDirectories(pages);
		Set<String> names = new HashSet<>();
		for (Path batch : batches)
			extract(batch, pages, names);
		if (names.size() != pageCount)
			throw new IOException("Expected " + pageCount + " OCR pages but received " + names.size());
		StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
		StringBuilder latex = new StringBuilder("""
				% !TEX TS-program = xelatex
				\\documentclass[11pt]{article}
				\\usepackage{fontspec}
				\\usepackage[margin=1in]{geometry}
				\\usepackage[hidelinks]{hyperref}
				\\setlength{\\parindent}{0pt}
				\\setlength{\\parskip}{0.75em}
				\\title{${TITLE}}
				\\date{}
				\\begin{document}
				\\maketitle

				""".replace("${TITLE}", escapeLatex(title)));
		for (int page = firstPage; page < firstPage + pageCount; page++) {
			Path text = pages.resolve("page-%06d.txt".formatted(page));
			if (!Files.isRegularFile(text))
				throw new IOException("Missing OCR output for page " + page);
			String pageText = Files.readString(text, StandardCharsets.UTF_8).strip();
			markdown.append("## Page ").append(page).append("\n\n").append(pageText).append("\n\n");
			latex.append(escapeLatex(pageText).replace("\n\n", "\n\n\\par\n")).append('\n');
			if (page < firstPage + pageCount - 1)
				latex.append("\\newpage\n\n");
		}
		latex.append("\\end{document}\n");
		Path document = outputDirectory.resolve("document.md");
		Path latexDocument = outputDirectory.resolve("document.tex");
		Files.writeString(document, markdown, StandardCharsets.UTF_8);
		Files.writeString(latexDocument, latex, StandardCharsets.UTF_8);
		return new Result(document, latexDocument, pages);
	}

	static String escapeLatex(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			escaped.append(switch (character) {
				case '\\' -> "\\textbackslash{}";
				case '{' -> "\\{";
				case '}' -> "\\}";
				case '$' -> "\\$";
				case '&' -> "\\&";
				case '#' -> "\\#";
				case '%' -> "\\%";
				case '_' -> "\\_";
				case '^' -> "\\textasciicircum{}";
				case '~' -> "\\textasciitilde{}";
				default -> String.valueOf(character);
			});
		}
		return escaped.toString();
	}

	private static void extract(Path batch, Path pages, Set<String> names) throws IOException {
		try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(batch)))) {
			for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
				String name = entry.getName();
				if (entry.isDirectory() || !name.matches("page-[0-9]{6}\\.txt") || !names.add(name))
					throw new IOException("Unexpected or duplicate OCR entry " + name);
				Files.copy(input, pages.resolve(name));
				input.closeEntry();
			}
		}
	}

	public record Result(Path document, Path latexDocument, Path pagesDirectory) {
	}
}
