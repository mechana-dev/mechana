package dev.mechana.plugins.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OcrMarkdownAssemblerTest {
	@TempDir
	Path temporary;

	@Test
	void assemblesPagesInDocumentOrderAcrossUnorderedBatches() throws Exception {
		Path second = batch("second.zip", 2, "second page");
		Path first = batch("first.zip", 1, "first page");
		OcrMarkdownAssembler.Result result = new OcrMarkdownAssembler().assemble(List.of(second, first),
				temporary.resolve("result"), 2, "Test Book");
		String markdown = Files.readString(result.document());
		assertTrue(markdown.indexOf("first page") < markdown.indexOf("second page"));
		assertEquals("# Test Book\n\n## Page 1\n\nfirst page\n\n## Page 2\n\nsecond page\n\n", markdown);
		String latex = Files.readString(result.latexDocument());
		assertTrue(latex.startsWith("% !TEX TS-program = xelatex\n"));
		assertTrue(latex.indexOf("first page") < latex.indexOf("second page"));
		assertFalse(latex.contains("\\section*{Page"));
		assertTrue(latex.contains("first page\n\\newpage\n\nsecond page\n"));
		assertTrue(latex.endsWith("\\end{document}\n"));
	}

	@Test
	void escapesLatexControlCharacters() {
		assertEquals("50\\% \\& \\#1\\_x \\$5 \\{ok\\} \\textbackslash{} \\textasciicircum{} \\textasciitilde{}",
				OcrMarkdownAssembler.escapeLatex("50% & #1_x $5 {ok} \\ ^ ~"));
	}

	@Test
	void constructsPortableTesseractCommand() {
		assertEquals(List.of("tesseract", "page.png", "page", "-l", "eng", "--psm", "1", "txt"),
				TesseractOcrPlugin.command("tesseract", Path.of("page.png"), Path.of("page"), "eng"));
	}

	private Path batch(String name, int page, String text) throws Exception {
		Path zip = temporary.resolve(name);
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
			output.putNextEntry(new ZipEntry("page-%06d.txt".formatted(page)));
			output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return zip;
	}
}
