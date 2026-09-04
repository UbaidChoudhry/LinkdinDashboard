package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.web.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises PDF and plain-text extraction, and the rejection paths: unsupported formats and
 * text that comes back too short to be a real resume (the image-only-scan case).
 */
class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void extractsTextFromPdf() throws IOException {
        String longSentence = "Experienced backend engineer with a decade of distributed systems work. ".repeat(6);
        byte[] pdfBytes = buildPdf(longSentence);

        String text = extractor.extract(pdfBytes, "resume.pdf", "application/pdf");

        assertThat(text).contains("Experienced backend engineer");
    }

    @Test
    void extractsPlainTextFile() {
        String body = "Senior Software Engineer. ".repeat(20);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(bytes, "resume.txt", "text/plain");

        assertThat(text).contains("Senior Software Engineer");
    }

    @Test
    void extractsMarkdownFile() {
        String body = "# Resume\n\n" + "Backend engineer with strong Java experience. ".repeat(10);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(bytes, "resume.md", "text/markdown");

        assertThat(text).contains("Backend engineer");
    }

    @Test
    void rejectsUnsupportedFormat() {
        byte[] bytes = "irrelevant".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(bytes, "resume.docx", "application/msword"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PDF")
                .hasMessageContaining(".txt")
                .hasMessageContaining(".md");
    }

    @Test
    void rejectsTooShortExtractedText() {
        byte[] bytes = "too short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(bytes, "resume.txt", "text/plain"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty")
                .hasMessageContaining("scanned image");
    }

    @Test
    void collapsesLongBlankRunsAndTrimsTrailingWhitespace() {
        String body = "Line one.   \n\n\n\n\nLine two. " + "Padding to clear the minimum length. ".repeat(6);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(bytes, "resume.txt", "text/plain");

        assertThat(text).doesNotContain("\n\n\n");
        assertThat(text).doesNotContain("Line one.   \n");
    }

    private byte[] buildPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                for (String line : wrap(text, 90)) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -14);
                }
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private String[] wrap(String text, int width) {
        return text.replaceAll("(.{" + width + "})", "$1\n").split("\n");
    }
}
