package com.ubaid.jobdash.apply;

/**
 * Normalizes a form question's visible label text into the stable key used to collide
 * near-duplicate phrasings of the same question onto one {@code profile_answer} row (its
 * {@code question_key} column is unique). Two postings rarely word a question identically -
 * different capitalization, a trailing asterisk marking it required, a trailing "?" or ":", or
 * just extra surrounding whitespace - and without normalization every posting would mint its own
 * row for what is really the same question.
 */
public final class QuestionKey {

    private QuestionKey() {
    }

    /**
     * Lowercases, trims, collapses internal whitespace runs to a single space, and strips a
     * single trailing {@code *}, {@code ?}, {@code :} (and any punctuation/whitespace around it)
     * so that, for example, {@code "Do you opt-in to receive WhatsApp messages? *"} and
     * {@code "do you opt-in to receive whatsapp messages"} normalize to the same key. Never
     * throws; a null input normalizes to the empty string.
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        String collapsed = question.toLowerCase().trim().replaceAll("\\s+", " ");
        // Strip trailing punctuation/whitespace repeatedly - handles "...? *", "...: ", "...*?" etc.
        String stripped = stripTrailingPunctuation(collapsed);
        // The model annotates labels despite being told not to - "Gender (voluntary EEO)" next to
        // "Gender" - so a trailing parenthetical group is dropped too, after the punctuation strip
        // (a required-marker "*" may follow it) and followed by another strip.
        String withoutAnnotation = stripped.replaceAll("\\s*\\([^()]*\\)$", "");
        return stripTrailingPunctuation(withoutAnnotation);
    }

    private static String stripTrailingPunctuation(String text) {
        String stripped = text;
        while (!stripped.isEmpty() && isTrailingPunctuationOrSpace(stripped.charAt(stripped.length() - 1))) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static boolean isTrailingPunctuationOrSpace(char c) {
        return c == '*' || c == '?' || c == ':' || c == '.' || c == ' ';
    }
}
