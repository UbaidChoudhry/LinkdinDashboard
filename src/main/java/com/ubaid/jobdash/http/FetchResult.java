package com.ubaid.jobdash.http;

/**
 * Outcome of {@link PacedHttpClient#fetch}. A sealed hierarchy so callers must handle each
 * distinct reason a request did or didn't happen — the rate limiter's run cap, the rolling
 * daily budget, and the circuit breaker all mean different things to a user watching a sweep.
 */
public sealed interface FetchResult {

    /** The request was sent and a response (of any status code) was received. */
    record Completed(int statusCode, String body, ResponseOutcome outcome, long waitedMs) implements FetchResult {
    }

    /** Refused by rate-limiter layer B: the per-run request cap was already reached. */
    record BlockedByRunCap(int runCap) implements FetchResult {
    }

    /** Refused by rate-limiter layer C: the rolling 24h budget was already reached. */
    record BlockedByDailyBudget(int currentCount, int cap) implements FetchResult {
    }

    /** Refused by the circuit breaker: it is OPEN, or HALF_OPEN with a probe already in flight. */
    record BlockedByCircuit() implements FetchResult {
    }

    /** The request was attempted but never received an HTTP response (I/O failure, interruption). */
    record TransportError(String message) implements FetchResult {
    }
}
