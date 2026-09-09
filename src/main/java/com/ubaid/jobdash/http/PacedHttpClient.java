package com.ubaid.jobdash.http;

import com.ubaid.jobdash.source.linkedin.CardParser;
import com.ubaid.jobdash.source.linkedin.DetailParser;
import com.ubaid.jobdash.source.linkedin.JobCard;
import com.ubaid.jobdash.source.linkedin.JobDetail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Wraps {@link HttpClient} with the three rate-limiting layers, the circuit breaker, request
 * budget recording, and response classification, so every outbound request to LinkedIn - a
 * search page via {@link #fetch} or a job-detail fragment via {@link #fetchDetail} - goes
 * through the same safety gates and draws on the same budget. Never throws on a non-2xx response — those are returned as
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
    private final DetailParser detailParser;
    private final Clock clock;

    public PacedHttpClient(HttpClient httpClient, RateLimiter rateLimiter, CircuitBreaker circuitBreaker,
                            RequestBudgetStore budgetStore, ResponseOutcomeDetector detector,
                            CardParser cardParser, Clock clock) {
        this(httpClient, rateLimiter, circuitBreaker, budgetStore, detector, cardParser, new DetailParser(), clock);
    }

    public PacedHttpClient(HttpClient httpClient, RateLimiter rateLimiter, CircuitBreaker circuitBreaker,
                            RequestBudgetStore budgetStore, ResponseOutcomeDetector detector,
                            CardParser cardParser, DetailParser detailParser, Clock clock) {
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.budgetStore = budgetStore;
        this.detector = detector;
        this.cardParser = cardParser;
        this.detailParser = detailParser;
        this.clock = clock;
    }

    /**
     * Marks the start of a new run: resets rate-limiter layer B (the per-run request counter).
     * Call exactly once, at the start of each LinkedIn run, before its first {@link #fetch} -
     * without this the "per-run" cap silently becomes a per-process cap and every run after the
     * first ends {@code capped} early.
     */
    public void beginRun() {
        rateLimiter.resetRunCount();
    }

    /**
     * Fetches {@code uri} (a LinkedIn job-search results page for {@code keyword} at offset
     * {@code start}), gated by the rate limiter and circuit breaker.
     */
    public FetchResult fetch(URI uri, int start, String keyword) {
        return send(uri, (statusCode, body) -> {
            List<JobCard> cards = cardParser.parse(body);
            int cardCount = cards.size();
            List<String> page1Titles = start == 0
                    ? cards.stream().map(JobCard::title).toList()
                    : List.of();
            return detector.classify(statusCode, body, start, cardCount, page1Titles, keyword);
        });
    }

    /**
     * Fetches {@code uri} (a LinkedIn guest job-detail fragment) through exactly the same gates as
     * {@link #fetch}: it consumes one unit of the per-run cap and the rolling 24h budget, waits
     * its turn at the pacing gate, is refused while the breaker is open, and is written to
     * {@code request_log}. The outcome is one of {@code OK} / {@code GONE} / {@code BLOCKED}; see
     * {@link ResponseOutcomeDetector#classifyDetail}.
     */
    public FetchResult fetchDetail(URI uri) {
        return send(uri, (statusCode, body) -> {
            Optional<JobDetail> detail = detailParser.parse(body);
            return detector.classifyDetail(statusCode, body, detail.isPresent());
        });
    }

    /** How a completed response is turned into an outcome; the one thing search and detail differ on. */
    private interface Classifier {
        ResponseOutcomeDetector.Classification classify(int statusCode, String body);
    }

    private FetchResult send(URI uri, Classifier classifier) {
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

            ResponseOutcomeDetector.Classification classification = classifier.classify(statusCode, body);

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
