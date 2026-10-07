package com.ubaid.jobdash.http;

import java.time.Instant;

/**
 * Narrow persistence port for the rolling request budget. Backed by the {@code request_log}
 * table in production (wired by a later task); an in-memory implementation is used in tests
 * so the rolling-24h budget can be asserted without a database.
 */
public interface RequestBudgetStore {

    /** Records one outbound request attempt. */
    void record(RequestRecord record);

    /** Counts requests recorded at or after {@code since}. */
    int countSince(Instant since);

    /**
     * Counts requests at or after {@code since} that LinkedIn answered with a block (an HTTP
     * response classified {@link ResponseOutcome#BLOCKED}). Transport failures are excluded - a
     * dropped connection says nothing about how LinkedIn regards this IP.
     */
    int countBlockedSince(Instant since);
}
