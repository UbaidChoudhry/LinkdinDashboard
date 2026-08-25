package com.ubaid.jobdash.http;

import java.time.Instant;

/**
 * One outbound HTTP attempt, as recorded for the rolling-daily-budget count and for audit.
 *
 * @param statusCode -1 when the request never received an HTTP response (e.g. transport failure).
 */
public record RequestRecord(
        Instant timestamp,
        String url,
        int statusCode,
        long waitedMs,
        ResponseOutcome outcome
) {
}
