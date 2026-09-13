package com.ubaid.jobdash.text;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/**
 * Small text-shaping helpers shared by the prompt builders that talk to the Claude CLI
 * ({@code ai.MatchPromptBuilder}, {@code apply.ApplyPromptBuilder}). Extracted here (rather than
 * left private in one of them) because both need identically-shaped job descriptions - stripped
 * of HTML and truncated to a caller-chosen length - and the two packages don't share visibility.
 */
public final class PromptText {

    private static final String TRUNCATION_MARKER = " …[truncated]";

    private PromptText() {
    }

    /**
     * Strips HTML, handling both real tags (Lever) and entity-escaped tags (Greenhouse) - ATS
     * boards hand back HTML descriptions in both disguises. Plain text (no tags, no entities)
     * passes through unchanged.
     */
    public static String stripHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String unescaped = Parser.unescapeEntities(raw, false);
        return Jsoup.parse(unescaped).text();
    }

    /** Cuts on a word boundary at {@code maxChars} and appends an explicit truncation marker. */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String cut = text.substring(0, maxChars);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > 0) {
            cut = cut.substring(0, lastSpace);
        }
        return cut + TRUNCATION_MARKER;
    }
}
