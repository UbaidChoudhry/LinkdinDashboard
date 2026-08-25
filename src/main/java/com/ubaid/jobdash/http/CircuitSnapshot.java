package com.ubaid.jobdash.http;

import java.time.Instant;

/**
 * The circuit breaker's persisted state.
 *
 * @param consecutiveTrips number of trips since the breaker last fully closed via a
 *                          successful probe; drives the doubling cooldown.
 * @param softFailureCount soft failures accumulated while CLOSED; reset by any success.
 * @param openedAt         when the breaker most recently opened, or null if never opened.
 * @param openUntil        when the cooldown expires and a probe may be admitted, or null
 *                          unless the breaker is OPEN.
 */
public record CircuitSnapshot(
        CircuitState state,
        int consecutiveTrips,
        int softFailureCount,
        Instant openedAt,
        Instant openUntil
) {
    public static CircuitSnapshot initial() {
        return new CircuitSnapshot(CircuitState.CLOSED, 0, 0, null, null);
    }
}
