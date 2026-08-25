package com.ubaid.jobdash.http;

import com.ubaid.jobdash.source.linkedin.CardParser;
import com.ubaid.jobdash.source.linkedin.JobCard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;

/**
 * Wraps {@link HttpClient} with the three rate-limiting layers, the circuit breaker, request
 * budget recording, and response classification, so every outbound request to LinkedIn goes
 * through the same safety gates. Never throws on a non-2xx response — those are returned as
 * a typed {@link FetchResult.Completed} for the caller to interpret via its {@code outcome}.
 */
public final class PacedHttpClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final RequestBudgetStore budgetStore;
    private final ResponseOutcomeDetector detector;
    private final CardParser cardParser;
    private final Clock clock;

    public PacedHttpClient(HttpClient httpClient, RateLimiter rateLimiter, CircuitBreaker circuitBreaker,
                            RequestBudgetStore budgetStore, ResponseOutcomeDetector detector,
                            CardParser cardParser, Clock clock) {
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.budgetStore = budgetStore;
        this.detector = detector;
        this.cardParser = cardParser;
        this.clock = clock;
    }

    /**
     * Fetches {@code uri} (a LinkedIn job-search results page for {@code keyword} at offset
     * {@code start}), gated by the rate limiter and circuit breaker.
     */
    public FetchResult fetch(URI uri, int start, String keyword) {
        RateLimiter.Decision decision = rateLimiter.checkBudget();
        if (decision instanceof RateLimiter.Decision.BlockedByRunCap b) {
            return new FetchResult.BlockedByRunCap(b.runCap());
        }
        if (decision instanceof RateLimiter.Decision.BlockedByDailyBudget b) {
            return new FetchResult.BlockedByDailyBudget(b.currentCount(), b.cap());
        }

        if (circuitBreaker.tryAcquire() == CircuitBreaker.Admission.REFUSED) {
            return new FetchResult.BlockedByCircuit();
        }

        boolean breakerResolved = false;
        try {
            rateLimiter.markRequestStarted();

            RateLimiter.PacingPermit permit;
            try {
                permit = rateLimiter.acquirePacing();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult.TransportError("interrupted while waiting for pacing slot");
            }

            int statusCode;
            String body;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", ACCEPT_LANGUAGE)
                        .GET()
                        .build();
                HttpResponse<String> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    circuitBreaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
                    breakerResolved = true;
                    budgetStore.record(new RequestRecord(clock.instant(), uri.toString(), -1,
                            permit.waitedMs(), ResponseOutcome.BLOCKED));
                    return new FetchResult.TransportError(String.valueOf(e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    circuitBreaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
                    breakerResolved = true;
                    budgetStore.record(new RequestRecord(clock.instant(), uri.toString(), -1,
                            permit.waitedMs(), ResponseOutcome.BLOCKED));
                    return new FetchResult.TransportError("interrupted while sending request");
                }
                statusCode = response.statusCode();
                body = response.body();
            } finally {
                permit.close();
            }

            List<JobCard> cards = cardParser.parse(body);
            int cardCount = cards.size();
            List<String> page1Titles = start == 0
                    ? cards.stream().map(JobCard::title).toList()
                    : List.of();

            ResponseOutcomeDetector.Classification classification =
                    detector.classify(statusCode, body, start, cardCount, page1Titles, keyword);

            if (statusCode == 429 || statusCode == 999) {
                circuitBreaker.recordFailure(CircuitBreaker.FailureKind.HARD);
            } else if (classification.outcome() == ResponseOutcome.BLOCKED || classification.unparseable()) {
                circuitBreaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
            } else {
                circuitBreaker.recordSuccess();
            }
            breakerResolved = true;

            budgetStore.record(new RequestRecord(clock.instant(), uri.toString(), statusCode,
                    permit.waitedMs(), classification.outcome()));

            return new FetchResult.Completed(statusCode, body, classification.outcome(), permit.waitedMs());
        } finally {
            if (!breakerResolved) {
                circuitBreaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
            }
        }
    }
}
