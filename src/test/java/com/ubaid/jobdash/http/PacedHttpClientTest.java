package com.ubaid.jobdash.http;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.InMemoryCircuitStateStore;
import com.ubaid.jobdash.http.support.InMemoryRequestBudgetStore;
import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.source.search.CardParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PacedHttpClient} end to end against {@link StubHttpClient} - no real
 * network calls are made anywhere in this test.
 */
class PacedHttpClientTest {

    private static final URI URI_UNDER_TEST = URI.create("https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords=engineer&start=0");

    private record Fixture(PacedHttpClient client, StubHttpClient http, InMemoryCircuitStateStore circuitStore,
                            InMemoryRequestBudgetStore budgetStore, FakeClock clock) {
    }

    private Fixture newFixture(int perRunCap, int perRollingDayCap) {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        InMemoryRequestBudgetStore budgetStore = new InMemoryRequestBudgetStore();
        InMemoryCircuitStateStore circuitStore = new InMemoryCircuitStateStore();
        RateLimiter rateLimiter = new RateLimiter(clock, sleeper, budgetStore,
                Duration.ofSeconds(6), Duration.ofSeconds(12), perRunCap, perRollingDayCap);
        CircuitBreaker breaker = new CircuitBreaker(circuitStore, clock,
                Duration.ofMinutes(30), Duration.ofMinutes(60), 2);
        StubHttpClient http = new StubHttpClient();
        PacedHttpClient client = new PacedHttpClient(http, rateLimiter, breaker, budgetStore,
                new ResponseOutcomeDetector(), new CardParser(), clock);
        return new Fixture(client, http, circuitStore, budgetStore, clock);
    }

    private static final String TEN_CARD_BODY_TEMPLATE = """
            <ul>%s</ul>
            """;

    private String bodyWithCards(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            long id = 1000 + i;
            sb.append("""
                    <li>
                      <div class="base-card" data-entity-urn="urn:li:jobPosting:%d">
                        <a class="base-card__full-link" href="https://www.linkedin.com/jobs/view/%d">link</a>
                        <h3 class="base-search-card__title">Software Engineer %d</h3>
                        <h4 class="base-search-card__subtitle">Acme Corp</h4>
                        <span class="job-search-card__location">Remote</span>
                      </div>
                    </li>
                    """.formatted(id, id, i));
        }
        return TEN_CARD_BODY_TEMPLATE.formatted(sb);
    }

    @Test
    void successfulResponseIsClassifiedOkAndRecordsBudgetAndBreakerSuccess() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(200, bodyWithCards(10), URI_UNDER_TEST));

        FetchResult result = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");

        assertInstanceOf(FetchResult.Completed.class, result);
        FetchResult.Completed completed = (FetchResult.Completed) result;
        assertEquals(200, completed.statusCode());
        assertEquals(ResponseOutcome.OK, completed.outcome());
        assertEquals(0L, completed.waitedMs(), "first request should not have waited");

        assertEquals(1, f.budgetStore.all().size());
        assertEquals(CircuitState.CLOSED, f.circuitStore.load().state());
    }

    @Test
    void sendsRealisticUserAgentAndAcceptLanguageHeaders() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(200, bodyWithCards(10), URI_UNDER_TEST));

        f.client.fetch(URI_UNDER_TEST, 0, "software engineer");

        HttpRequest sent = f.http.requestsSeen().get(0);
        String userAgent = sent.headers().firstValue("User-Agent").orElse("");
        assertTrue(userAgent.contains("Mozilla") && userAgent.contains("Chrome"),
                "expected a realistic desktop browser User-Agent, got: " + userAgent);
        assertEquals("en-US,en;q=0.9", sent.headers().firstValue("Accept-Language").orElse(""));
    }

    @Test
    void neverThrowsOnNonTwoxxReturnsTypedOutcomeInstead() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(400, "<html>bad request</html>", URI_UNDER_TEST));

        FetchResult result = f.client.fetch(URI_UNDER_TEST, 1000, "software engineer");

        assertInstanceOf(FetchResult.Completed.class, result);
        assertEquals(ResponseOutcome.PAST_CAP, ((FetchResult.Completed) result).outcome());
    }

    @Test
    void http429TripsBreakerAndSubsequentFetchIsBlockedByCircuit() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(429, "", URI_UNDER_TEST));

        FetchResult first = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");
        assertInstanceOf(FetchResult.Completed.class, first);
        assertEquals(ResponseOutcome.BLOCKED, ((FetchResult.Completed) first).outcome());
        assertEquals(CircuitState.OPEN, f.circuitStore.load().state());

        FetchResult second = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");
        assertInstanceOf(FetchResult.BlockedByCircuit.class, second);
        // The circuit refused before any further HTTP call was attempted.
        assertEquals(1, f.http.requestsSeen().size());
    }

    @Test
    void runCapBlocksBeforeAnyHttpCallIsMade() {
        Fixture f = newFixture(0, 300);

        FetchResult result = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");

        assertInstanceOf(FetchResult.BlockedByRunCap.class, result);
        assertEquals(0, f.http.requestsSeen().size());
    }

    @Test
    void dailyBudgetBlocksBeforeAnyHttpCallIsMade() {
        Fixture f = newFixture(150, 0);

        FetchResult result = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");

        assertInstanceOf(FetchResult.BlockedByDailyBudget.class, result);
        assertEquals(0, f.http.requestsSeen().size());
    }

    @Test
    void transportFailureIsReportedNotThrown() {
        Fixture f = newFixture(150, 300);
        f.http.enqueueFailure(new java.io.IOException("connection reset"));

        FetchResult result = f.client.fetch(URI_UNDER_TEST, 0, "software engineer");

        assertInstanceOf(FetchResult.TransportError.class, result);
        // A transport failure counts as a soft failure against the breaker.
        assertEquals(1, f.circuitStore.load().softFailureCount());
    }

    // --- job-detail fragments -----------------------------------------------

    private static final URI DETAIL_URI = URI.create("https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/4239977994");
    private static final String DETAIL_BODY = """
            <section class="top-card-layout"><h2 class="top-card-layout__title">Engineer</h2></section>
            <div class="show-more-less-html__markup">We build distributed systems. Requirements: Java.</div>
            """;

    @Test
    void detailFetchIsClassifiedOkAndSpendsTheSharedBudget() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, DETAIL_URI));

        FetchResult result = f.client.fetchDetail(DETAIL_URI);

        FetchResult.Completed completed = assertInstanceOf(FetchResult.Completed.class, result);
        assertEquals(ResponseOutcome.OK, completed.outcome());
        assertEquals(1, f.budgetStore.all().size(), "a detail fetch must be counted in request_log like a search page");
        assertEquals(ResponseOutcome.OK, f.budgetStore.all().get(0).outcome());
        assertEquals(CircuitState.CLOSED, f.circuitStore.load().state());
    }

    @Test
    void detail404IsGoneAndDoesNotTouchTheBreaker() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(404, "", DETAIL_URI));

        FetchResult result = f.client.fetchDetail(DETAIL_URI);

        assertEquals(ResponseOutcome.GONE, assertInstanceOf(FetchResult.Completed.class, result).outcome());
        assertEquals(0, f.circuitStore.load().softFailureCount());
        assertEquals(CircuitState.CLOSED, f.circuitStore.load().state());
    }

    @Test
    void detail429TripsBreakerAndNextDetailFetchIsRefused() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(429, "", DETAIL_URI));

        FetchResult first = f.client.fetchDetail(DETAIL_URI);
        assertEquals(ResponseOutcome.BLOCKED, assertInstanceOf(FetchResult.Completed.class, first).outcome());
        assertEquals(CircuitState.OPEN, f.circuitStore.load().state());

        assertInstanceOf(FetchResult.BlockedByCircuit.class, f.client.fetchDetail(DETAIL_URI));
        assertEquals(1, f.http.requestsSeen().size());
    }

    @Test
    void detailWithoutADescriptionIsASoftFailure() {
        Fixture f = newFixture(150, 300);
        f.http.enqueue(new StubHttpResponse(200, "<html>please sign in</html>", DETAIL_URI));

        FetchResult result = f.client.fetchDetail(DETAIL_URI);

        assertEquals(ResponseOutcome.BLOCKED, assertInstanceOf(FetchResult.Completed.class, result).outcome());
        assertEquals(1, f.circuitStore.load().softFailureCount());
    }

    @Test
    void searchAndDetailFetchesShareOnePerRunCapUntilBeginRunResetsIt() {
        Fixture f = newFixture(2, 300);
        f.http.enqueue(new StubHttpResponse(200, bodyWithCards(10), URI_UNDER_TEST));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, DETAIL_URI));

        assertInstanceOf(FetchResult.Completed.class, f.client.fetch(URI_UNDER_TEST, 0, "software engineer"));
        assertInstanceOf(FetchResult.Completed.class, f.client.fetchDetail(DETAIL_URI));
        assertInstanceOf(FetchResult.BlockedByRunCap.class, f.client.fetchDetail(DETAIL_URI));

        // Without this reset the "per-run" cap would carry over into the next run.
        f.client.beginRun();
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, DETAIL_URI));
        assertInstanceOf(FetchResult.Completed.class, f.client.fetchDetail(DETAIL_URI));
    }
}
