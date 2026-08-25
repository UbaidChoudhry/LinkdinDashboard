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
}
