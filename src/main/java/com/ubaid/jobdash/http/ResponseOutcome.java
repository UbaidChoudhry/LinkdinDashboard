package com.ubaid.jobdash.http;

/**
 * The five ways a LinkedIn job-search response can be classified. See
 * {@link ResponseOutcomeDetector} for the exact detection rules.
 */
public enum ResponseOutcome {
    /** At least one parseable job card is present. */
    OK,
    /** The 26-byte end-of-results sentinel, or a page with fewer than 10 cards. */
    END_OF_RESULTS,
    /** HTTP 400 — the requested {@code start} offset is past LinkedIn's page cap. */
    PAST_CAP,
    /** HTTP 429 or 999, or an empty body at {@code start == 0}. */
    BLOCKED,
    /** Fewer than 30% of page-1 titles contain any keyword token. */
    IRRELEVANT
}
