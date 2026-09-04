package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns uploaded resume bytes into normalized plain text - the form actually sent to Claude for
 * job-match comparison. Supports PDF ({@code application/pdf}) and plain text (.txt/.md,
 * {@code text/*}); anything else is rejected with a message naming what IS accepted.
 */
@Component
public class ResumeTextExtractor {

    /** Below this many characters, the extraction is treated as having failed silently. */
    private static final int MIN_CHARS = 200;

    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");

    /** Extracts and normalizes plain text from an uploaded file's bytes. */
    public String extract(byte[] bytes, String originalFilename, String contentType) {
        String lowerName = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        boolean looksPdf = "application/pdf".equalsIgnoreCase(contentType) || lowerName.endsWith(".pdf");
        boolean looksText = (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/"))
                || lowerName.endsWith(".txt") || lowerName.endsWith(".md");

        String raw;
        if (looksPdf) {
            raw = extractPdf(bytes);
        } else if (looksText) {
            raw = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unsupported resume format" + (contentType == null ? "" : " '" + contentType + "'")
                            + ". Upload a PDF (.pdf), plain text (.txt), or Markdown (.md) file.");
        }

        String normalized = normalize(raw);
        if (normalized.length() < MIN_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That file looked empty after text extraction (only " + normalized.length()
                            + " characters). If this is a PDF, make sure it contains selectable text rather than "
                            + "a scanned image - image-only PDFs have no text to extract.");
        }
        return normalized;
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Could not read that PDF. Make sure it isn't corrupted or password-protected.");
        }
    }

    /** Collapses runs of 3+ blank lines to one, and trims trailing whitespace per line. */
    private String normalize(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            sb.append(stripTrailing(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return BLANK_RUNS.matcher(sb.toString()).replaceAll("\n\n").strip();
    }

    private String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
