package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.http.CircuitBreaker;
import com.ubaid.jobdash.http.PacedHttpClient;
import com.ubaid.jobdash.http.RateLimiter;
import com.ubaid.jobdash.http.ResponseOutcomeDetector;
import com.ubaid.jobdash.http.SweepProperties;
import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.InMemoryCircuitStateStore;
import com.ubaid.jobdash.http.support.InMemoryRequestBudgetStore;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.salary.SalaryEnrichmentService;
import com.ubaid.jobdash.source.search.CardParser;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Exercises {@link SweepService}'s pagination loop against a stubbed {@link HttpClient} - no
 * real network calls are made anywhere in this test. {@link JobListingRepository} and
 * {@link SweepRunRepository} are mocked (they're concrete JDBC classes, not ports); the T4
 * rate-limiting/circuit-breaking stack ({@link RateLimiter}, {@link CircuitBreaker},
 * {@link PacedHttpClient}) is wired for real, exactly as {@code PacedHttpClientTest} does, so
 * this test also proves the wiring (T5's job) behaves correctly end to end.
 */
@ExtendWith(MockitoExtension.class)
class SweepServiceTest {

    private static final long RUN_ID = 42L;

    @Mock
    private JobListingRepository jobListingRepository;
    @Mock
    private SweepRunRepository sweepRunRepository;
    @Mock
    private FilterEngine filterEngine;

    private record Fixture(SweepService service, DynamicHttpClient http, FakeClock clock,
                           RecordingEnrichment enrichment) {
    }

    /** A no-op {@link SalaryEnrichmentService} that just counts calls; keeps the sweep tests offline. */
    private static final class RecordingEnrichment extends SalaryEnrichmentService {
        private final java.util.List<Long> runIds = new java.util.ArrayList<>();

        RecordingEnrichment() {
            super(List.of(), null, null, null, null);
        }

        @Override
        public void enrichRun(long runId, String location, java.util.function.BooleanSupplier cancelled) {
            runIds.add(runId);
        }

        int calls() {
            return runIds.size();
        }
    }

    private Fixture newFixture(BiFunction<Integer, HttpRequest, HttpResponse<String>> responder,
                                int testModePageCap) {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(RUN_ID);
        lenient().when(jobListingRepository.upsertAll(any(), anyLong(), any()))
                .thenAnswer(inv -> {
                    List<JobCardInsert> inserts = inv.getArgument(0);
                    return inserts.size();
                });

        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        InMemoryRequestBudgetStore budgetStore = new InMemoryRequestBudgetStore();
        InMemoryCircuitStateStore circuitStore = new InMemoryCircuitStateStore();
        RateLimiter rateLimiter = new RateLimiter(clock, sleeper, budgetStore,
                Duration.ofSeconds(6), Duration.ofSeconds(12), 150, 300);
        CircuitBreaker breaker = new CircuitBreaker(circuitStore, clock,
                Duration.ofMinutes(30), Duration.ofMinutes(60), 2);
        DynamicHttpClient http = new DynamicHttpClient(responder);
        PacedHttpClient pacedHttpClient = new PacedHttpClient(http, rateLimiter, breaker, budgetStore,
                new ResponseOutcomeDetector(), new CardParser(), clock);

        SweepShardsProperties shardsProperties = new SweepShardsProperties(null);
        SweepProperties sweepProperties = new SweepProperties(
                new SweepProperties.Pacing(Duration.ofSeconds(6), Duration.ofSeconds(12)),
                new SweepProperties.Budget(150, 300, testModePageCap),
                new SweepProperties.Breaker(Duration.ofMinutes(30), Duration.ofMinutes(60), 2),
                new SweepProperties.Detail(true, 60));

        RecordingEnrichment enrichment = new RecordingEnrichment();
        SweepService service = new SweepService(pacedHttpClient, new CardParser(), filterEngine,
                jobListingRepository, sweepRunRepository, shardsProperties, sweepProperties, enrichment,
                new RunProgressRegistry(), clock);
        return new Fixture(service, http, clock, enrichment);
    }

    @Test
    void perRunPageCapOverridesTestModeDefault() {
        // Explicit per-run cap of 2 must win over the configured test-mode default of 5.
        Fixture f = newFixture((start, req) -> ok(start, 10), 5);
        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area",
                24, true, false, null, 2));
        assertThat(f.http.requestCount()).isEqualTo(2);
    }

    @Test
    void perRunPageCapAppliesEvenWhenTestModeIsOff() {
        // A cap outside test mode is still honoured; without it this page responder never ends.
        Fixture f = newFixture((start, req) -> ok(start, 10), 3);
        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area",
                24, false, false, null, 4));
        assertThat(f.http.requestCount()).isEqualTo(4);
    }

    @Test
    void sweepStampsVerdictsOnRowsItWrites() {
        // Filtering belongs to the sweep: every page that stores rows must trigger evaluation,
        // otherwise freshly-swept rows sit with a null verdict and never reach the search tab.
        Fixture f = newFixture((start, req) -> start < 20 ? ok(start, 10) : ok(start, 0), 10);
        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area",
                24, false, false, null, null));
        verify(filterEngine, atLeastOnce()).evaluateNewRows();
    }

    @Test
    void sweepRunsSalaryEnrichmentAfterAnOkPage() {
        // Enrichment is wired inline after filtering: an OK page that stores rows must trigger it.
        Fixture f = newFixture((start, req) -> start < 20 ? ok(start, 10) : ok(start, 0), 10);
        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area",
                24, false, false, null, null));
        assertThat(f.enrichment.calls()).isGreaterThanOrEqualTo(1);
    }

    // --- test doubles -------------------------------------------------------------------

    /** Same shape as StubHttpClient, but answers dynamically based on the request's start=. */
    private static final class DynamicHttpClient extends HttpClient {
        private final BiFunction<Integer, HttpRequest, HttpResponse<String>> responder;
        private final AtomicInteger requestCount = new AtomicInteger(0);

        DynamicHttpClient(BiFunction<Integer, HttpRequest, HttpResponse<String>> responder) {
            this.responder = responder;
        }

        int requestCount() {
            return requestCount.get();
        }

        private static int startOf(HttpRequest request) {
            String query = request.uri().getQuery();
            for (String pair : query.split("&")) {
                if (pair.startsWith("start=")) {
                    return Integer.parseInt(pair.substring("start=".length()));
                }
            }
            throw new IllegalStateException("no start= param in " + request.uri());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
                throws IOException, InterruptedException {
            requestCount.incrementAndGet();
            return (HttpResponse<T>) responder.apply(startOf(request), request);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> h,
                                                                  HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }
    }

    private static String pageBody(int count, String titlePrefix, long idBase) {
        StringBuilder sb = new StringBuilder("<ul>");
        for (int i = 0; i < count; i++) {
            long id = idBase + i;
            sb.append("""
                    <li>
                      <div class="base-card" data-entity-urn="urn:li:jobPosting:%d">
                        <a class="base-card__full-link" href="https://www.linkedin.com/jobs/view/%d">link</a>
                        <h3 class="base-search-card__title">%s %d</h3>
                        <h4 class="base-search-card__subtitle">Acme Corp</h4>
                        <span class="job-search-card__location">Remote</span>
                      </div>
                    </li>
                    """.formatted(id, id, titlePrefix, i));
        }
        return sb.append("</ul>").toString();
    }

    private static StubHttpResponse ok(int start, int cardCount) {
        return new StubHttpResponse(200, pageBody(cardCount, "Software Engineer", 1000L + start), null);
    }

    // --- scenarios ------------------------------------------------------------------------

    @Test
    void normalRunEndsOnEndOfResultsAfterThreePages() {
        Fixture f = newFixture((start, req) -> switch (start) {
            case 0, 10 -> ok(start, 10);
            case 20 -> ok(start, 5); // fewer than 10 -> END_OF_RESULTS
            default -> throw new IllegalStateException("unexpected start=" + start);
        }, 3);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("ok");
        assertThat(progress.pagesFetched()).isEqualTo(3);
        assertThat(progress.requestsMade()).isEqualTo(3);
        // Only OK pages are parsed/stored; the END_OF_RESULTS page's 5 cards are not counted -
        // per spec, END_OF_RESULTS just stops the loop, it doesn't accumulate counters.
        assertThat(progress.cardsSeen()).isEqualTo(20);
        assertThat(progress.saturated()).isFalse();
        verify(sweepRunRepository).finish(eq(RUN_ID), any(), eq("ok"));
    }

    @Test
    void saturationIsSetWhenStart990ReturnsAFullPageEvenThoughRunEndsNormally() {
        Fixture f = newFixture((start, req) -> {
            if (start <= 990) {
                return ok(start, 10);
            }
            return ok(start, 0); // start=1000: blank body -> END_OF_RESULTS
        }, Integer.MAX_VALUE);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("ok");
        assertThat(progress.saturated()).isTrue();
        assertThat(progress.pagesFetched()).isEqualTo(100 + 1); // start 0..990 (100 pages) + start=1000
        assertThat(f.http.requestCount()).isEqualTo(101);
    }

    @Test
    void pastCapStopsAndFlagsSaturated() {
        Fixture f = newFixture((start, req) ->
                new StubHttpResponse(400, "<html>bad request</html>", null), 3);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("ok");
        assertThat(progress.saturated()).isTrue();
        assertThat(progress.pagesFetched()).isEqualTo(1);
    }

    @Test
    void blockedResponseFinishesRunAsBlocked() {
        Fixture f = newFixture((start, req) -> new StubHttpResponse(429, "", null), 3);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("blocked");
        verify(sweepRunRepository).finish(eq(RUN_ID), any(), eq("blocked"));
    }

    @Test
    void irrelevantFirstPageFinishesAsFailedAndStoresNothing() {
        Fixture f = newFixture((start, req) ->
                new StubHttpResponse(200, pageBody(10, "Registered Nurse", 1000L), null), 3);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("failed");
        verify(jobListingRepository, never()).upsertAll(any(), anyLong(), any());
    }

    @Test
    void testModePageCapStopsTheRunAfterConfiguredPages() {
        Fixture f = newFixture((start, req) -> ok(start, 10), 3);

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, true, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("ok");
        assertThat(progress.pagesFetched()).isEqualTo(3);
        assertThat(f.http.requestCount()).isEqualTo(3);
    }

    @Test
    void cancellationMidRunStopsBeforeTheNextPageAndFinishesAsCancelled() {
        Fixture[] holder = new Fixture[1];
        Fixture f = newFixture((start, req) -> {
            if (start == 10) {
                holder[0].service().cancel(RUN_ID);
            }
            return ok(start, 10);
        }, 100);
        holder[0] = f;

        f.service.run(RUN_ID, new SweepRunRequest("software engineer", "New York City Metropolitan Area", 24, false, false, null, null));

        SweepProgress progress = f.service.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("cancelled");
        assertThat(progress.pagesFetched()).isEqualTo(2); // start=0 and start=10 both completed
        assertThat(f.http.requestCount()).isEqualTo(2); // never attempted start=20
    }

    // Mockito's any()/anyInt() etc. need static imports; kept local to avoid clashing with
    // ArgumentMatchers.any() used elsewhere in this file.
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
