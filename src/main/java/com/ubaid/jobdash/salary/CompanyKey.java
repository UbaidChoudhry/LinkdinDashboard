package com.ubaid.jobdash.salary;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes a company name into a stable lookup key for the salary caches: lowercased, legal
 * suffixes and region qualifiers stripped, punctuation removed, whitespace collapsed.
 * <p>
 * Example: {@code "COGNIZANT TECHNOLOGY SOLUTIONS US CORP"} -> {@code "cognizant technology solutions"};
 * {@code "Ernst & Young U.S. LLP"} -> {@code "ernst young"}.
 */
public final class CompanyKey {

    private CompanyKey() {
    }

    /**
     * Trailing tokens stripped repeatedly from the end of the name. Multi-word entries are
     * checked before single-word ones so "private limited" and "north america" match whole.
     */
    private static final List<String> TRAILING_TOKENS = List.of(
            "private limited", "north america", "u s a", "u s",
            "inc", "llc", "corp", "corporation", "co", "ltd", "limited", "lp", "llp", "plc",
            "gmbh", "pvt", "usa", "us");

    private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Returns the normalized key, or {@code ""} for a null/blank input. */
    public static String of(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return "";
        }
        String s = companyName.toLowerCase();
        // Remove punctuation early so "inc.", "l.l.c.", "u.s." collapse onto their bare tokens.
        s = PUNCTUATION.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String token : TRAILING_TOKENS) {
                if (s.equals(token)) {
                    return "";
                }
                if (s.endsWith(" " + token)) {
                    s = s.substring(0, s.length() - token.length() - 1).trim();
                    changed = true;
                }
            }
        }
        return s;
    }
}
