package com.ubaid.jobdash.salary;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalizes a job title into a stable lookup key: lowercased, leading/trailing seniority and
 * level tokens removed, trailing requisition-id fragments dropped, punctuation stripped (except
 * spaces and {@code +}, so {@code c++} survives), whitespace collapsed.
 * <p>
 * Examples: {@code "Sr. Software Engineer II"} -> {@code "software engineer"};
 * {@code "SENIOR ORACLE APPLICATION DEVELOPER"} -> {@code "oracle application developer"}.
 */
public final class TitleKey {

    private TitleKey() {
    }

    /** Seniority / level words stripped from either end of the title, repeatedly. */
    private static final Set<String> EDGE_TOKENS = Set.of(
            "senior", "sr", "junior", "jr", "lead", "staff", "principal",
            "mid", "i", "ii", "iii", "iv", "v");

    /** Two-word leading phrases stripped from the front. */
    private static final String[] LEADING_PHRASES = {"entry level"};

    private static final Pattern REQ_ID = Pattern.compile("\\b[a-z]{3,}\\d{3,}\\b");
    private static final Pattern JC_TAG = Pattern.compile("\\bjc\\d+\\b");
    private static final Pattern TRAILING_DASH_NUMWORD = Pattern.compile("\\s*-\\s*\\d+\\s*-\\s*[a-z]{2}\\s*$");
    private static final Pattern TRAILING_DASH_DIGITS = Pattern.compile("\\s*-\\s*\\d+\\s*$");
    private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9 +]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Returns the normalized key, or {@code ""} for a null/blank input. */
    public static String of(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return "";
        }
        String s = jobTitle.toLowerCase();

        // Drop a trailing " - <req-id-looking fragment>" before anything else.
        int dash = s.lastIndexOf(" - ");
        if (dash >= 0) {
            String tail = s.substring(dash + 3);
            if (REQ_ID.matcher(tail).find() || tail.matches("\\s*[a-z0-9]{6,}\\s*")) {
                s = s.substring(0, dash);
            }
        }

        s = TRAILING_DASH_NUMWORD.matcher(s).replaceAll("");
        s = TRAILING_DASH_DIGITS.matcher(s).replaceAll("");
        s = JC_TAG.matcher(s).replaceAll(" ");
        s = REQ_ID.matcher(s).replaceAll(" ");

        for (String phrase : LEADING_PHRASES) {
            if (s.trim().startsWith(phrase)) {
                s = s.trim().substring(phrase.length());
            }
        }

        s = PUNCTUATION.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();

        // Strip seniority/level tokens from both ends.
        String[] words = s.isEmpty() ? new String[0] : s.split(" ");
        int start = 0;
        int end = words.length;
        while (start < end && EDGE_TOKENS.contains(words[start])) {
            start++;
        }
        while (end > start && EDGE_TOKENS.contains(words[end - 1])) {
            end--;
        }
        return String.join(" ", java.util.Arrays.asList(words).subList(start, end));
    }
}
