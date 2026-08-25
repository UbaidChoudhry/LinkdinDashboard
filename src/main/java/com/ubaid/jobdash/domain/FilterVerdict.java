package com.ubaid.jobdash.domain;

/**
 * Outcome of the exclusion-word / company-blocklist filter on a {@link JobListing}.
 * Persisted as lowercase text (e.g. {@code pass}) so it matches the literal used by the
 * partial index predicate {@code filter_verdict = 'pass'} in {@code V1__init.sql}.
 */
public enum FilterVerdict {
    PASS,
    REJECT;

    public String toDb() {
        return name().toLowerCase();
    }

    public static FilterVerdict fromDb(String text) {
        return text == null ? null : FilterVerdict.valueOf(text.toUpperCase());
    }
}
