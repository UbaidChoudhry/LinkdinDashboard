package com.ubaid.jobdash.http;

/**
 * The ways a LinkedIn guest response can be classified. The first five apply to a job-search
 * page ({@link ResponseOutcomeDetector#classify}); a job-detail fragment
 * ({@link ResponseOutcomeDetector#classifyDetail}) yields only {@link #OK}, {@link #GONE} or
 * {@link #BLOCKED}.
 */
public enum ResponseOutcome {
    /** Search: at least one parseable job card is present. Detail: a description was parsed. */
    OK,
    /** The 26-byte end-of-results sentinel, or a page with fewer than 10 cards. */
    END_OF_RESULTS,
    /** HTTP 400 — the requested {@code start} offset is past LinkedIn's page cap. */
    PAST_CAP,
    /** HTTP 429 or 999, an empty body at {@code start == 0}, or an unusable detail response. */
    BLOCKED,
    /** Fewer than 30% of page-1 titles contain any keyword token. */
    IRRELEVANT,
    /** Detail only: HTTP 404 — the posting has been taken down. Never retried. */
    GONE
}
