package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.http.CircuitBreaker;
import com.ubaid.jobdash.http.PacedHttpClient;
import com.ubaid.jobdash.http.RateLimiter;
import com.ubaid.jobdash.http.ResponseOutcomeDetector;
import com.ubaid.jobdash.http.SweepProperties;
import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.InMemoryCircuitStateStore;
import com.ubaid.jobdash.http.support.InMemoryRequestBudgetStore;
import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.source.search.CardParser;
import com.ubaid.jobdash.source.search.DetailParser;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link DetailFetchService} against a real {@link PacedHttpClient} stack over
 * {@link StubHttpClient} - no network - with the repositories mocked, exactly as
 * {@link SweepServiceTest} does for the collection phase. Assertions are on the positive
 * outcome (which rows were written, with what) per HANDOFF.md §1.
 */
@ExtendWith(MockitoExtension.class)
class DetailFetchServiceTest {

    private static final long RUN_ID = 77L;
    private static final Instant T0 = Instant.parse("2026-09-09T12:00:00Z");

    private static final String DETAIL_BODY = """
            <div class="show-more-less-html__markup">We build distributed systems.<br>Requirements: Java.</div>
            <ul class="description__job-criteria-list">
              <li class="description__job-criteria-item">
                <h3 class="description__job-criteria-subheader">Seniority level</h3>
                <span class="description__job-criteria-text">Mid-Senior level</span>
              </li>
            </ul>
            """;

    @Mock
    private JobListingRepository jobListingRepository;
    @Mock
    private SweepRunRepository sweepRunRepository;

    private record Fixture(DetailFetchService service, StubHttpClient http, RunProgressRegistry registry,
                           InMemoryCircuitStateStore circuitStore) {
    }

    private Fixture newFixture(boolean enabled, int maxPerRun, int perRunCap) {
        FakeClock clock = new FakeClock(T0);
        FakeSleeper sleeper = new FakeSleeper(clock);
        InMemoryRequestBudgetStore budgetStore = new InMemoryRequestBudgetStore();
        InMemoryCircuitStateStore circuitStore = new InMemoryCircuitStateStore();
        RateLimiter rateLimiter = new RateLimiter(clock, sleeper, budgetStore,
                Duration.ofSeconds(6), Duration.ofSeconds(12), perRunCap, 300);
        CircuitBreaker breaker = new CircuitBreaker(circuitStore, clock,
                Duration.ofMinutes(30), Duration.ofMinutes(60), 2);
        StubHttpClient http = new StubHttpClient();
        DetailParser detailParser = new DetailParser();
        PacedHttpClient pacedHttpClient = new PacedHttpClient(http, rateLimiter, breaker, budgetStore,
                new ResponseOutcomeDetector(), new CardParser(), detailParser, clock);
        SweepProperties properties = new SweepProperties(
                new SweepProperties.Pacing(Duration.ofSeconds(6), Duration.ofSeconds(12)),
                new SweepProperties.Budget(perRunCap, 300, 3),
                new SweepProperties.Breaker(Duration.ofMinutes(30), Duration.ofMinutes(60), 2),
                new SweepProperties.Detail(enabled, maxPerRun));
        RunProgressRegistry registry = new RunProgressRegistry();
        // The collection phase left 7 requests on the counter; the detail phase must add to it.
        registry.start(RUN_ID, new SweepProgress(RUN_ID, "running", null, 3, 7, 30, 12, false,
                0, 0, 0, 0, "linkedin", null));
        DetailFetchService service = new DetailFetchService(pacedHttpClient, detailParser, jobListingRepository,
                sweepRunRepository, registry, properties, clock);
        return new Fixture(service, http, registry, circuitStore);
    }

    private static JobListing job(long jobId) {
        return new JobListing(jobId, String.valueOf(4000000000L + jobId), "linkedin", "Engineer " + jobId, "Acme",
                "New York, NY", T0, T0, T0, RUN_ID, "https://www.linkedin.com/jobs/view/" + jobId, null,
                FilterVerdict.PASS, 1, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null, null, null, null, null, null);
    }

    @Test
    void storesEveryDescriptionWithItsHashAndReportsProgress() {
        Fixture f = newFixture(true, 60, 150);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1), job(2), job(3)));
        for (int i = 0; i < 3; i++) {
            f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));
        }

        String status = f.service.fetchForRun(RUN_ID, () -> false);

        assertThat(status).isEqualTo("ok");
        String expectedHash = DetailParser.hashOf("We build distributed systems.\nRequirements: Java.");
        for (long id : List.of(1L, 2L, 3L)) {
            verify(jobListingRepository).applyDetail(eq(id), eq("We build distributed systems.\nRequirements: Java."),
                    eq(expectedHash), any(), any());
        }
        verify(jobListingRepository, never()).markDetailGone(anyLong(), any());
        assertThat(f.http.requestsSeen()).hasSize(3);
        assertThat(f.http.requestsSeen().get(0).uri().toString())
                .isEqualTo("https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/4000000001");

        SweepProgress progress = f.registry.progress(RUN_ID).orElseThrow();
        assertThat(progress.status()).isEqualTo("fetching_details");
        assertThat(progress.detailsDone()).isEqualTo(3);
        assertThat(progress.detailsTotal()).isEqualTo(3);
        assertThat(progress.requestsMade()).as("7 search pages + 3 details").isEqualTo(10);
        assertThat(progress.jobsNew()).as("collection counters survive the phase").isEqualTo(12);
        verify(sweepRunRepository).updateDetailProgress(RUN_ID, 3, 3, 10);
    }

    @Test
    void a404MarksTheRowGoneSoItIsNeverRetried() {
        Fixture f = newFixture(true, 60, 150);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1)));
        f.http.enqueue(new StubHttpResponse(404, "", null));

        String status = f.service.fetchForRun(RUN_ID, () -> false);

        assertThat(status).isEqualTo("ok");
        verify(jobListingRepository).markDetailGone(eq(1L), any());
        verify(jobListingRepository, never()).applyDetail(anyLong(), any(), any(), any(), any());
        assertThat(f.registry.progress(RUN_ID).orElseThrow().detailsDone()).isEqualTo(1);
    }

    @Test
    void a429StopsThePhaseLeavesEveryRowUntouchedAndOpensTheBreaker() {
        Fixture f = newFixture(true, 60, 150);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1), job(2), job(3)));
        f.http.enqueue(new StubHttpResponse(429, "", null));

        String status = f.service.fetchForRun(RUN_ID, () -> false);

        assertThat(status).isEqualTo("blocked");
        verify(jobListingRepository, never()).applyDetail(anyLong(), any(), any(), any(), any());
        verify(jobListingRepository, never()).markDetailGone(anyLong(), any());
        assertThat(f.http.requestsSeen()).as("no further request after the block").hasSize(1);
        assertThat(f.circuitStore.load().state()).isEqualTo(com.ubaid.jobdash.http.CircuitState.OPEN);
    }

    @Test
    void thePerRunCapStopsThePhaseAsCapped() {
        Fixture f = newFixture(true, 60, 1);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1), job(2)));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));

        String status = f.service.fetchForRun(RUN_ID, () -> false);

        assertThat(status).isEqualTo("capped");
        verify(jobListingRepository, times(1)).applyDetail(anyLong(), any(), any(), any(), any());
        assertThat(f.http.requestsSeen()).hasSize(1);
    }

    @Test
    void anUnusablePageIsSkippedAndThePhaseContinues() {
        Fixture f = newFixture(true, 60, 150);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1), job(2)));
        f.http.enqueue(new StubHttpResponse(200, "<html>please sign in</html>", null));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));

        String status = f.service.fetchForRun(RUN_ID, () -> false);

        assertThat(status).isEqualTo("ok");
        verify(jobListingRepository, never()).applyDetail(eq(1L), any(), any(), any(), any());
        verify(jobListingRepository).applyDetail(eq(2L), any(), any(), any(), any());
        assertThat(f.registry.progress(RUN_ID).orElseThrow().detailsDone()).isEqualTo(1);
        assertThat(f.registry.progress(RUN_ID).orElseThrow().requestsMade()).isEqualTo(9);
    }

    @Test
    void theQueueIsBoundedByMaxPerRun() {
        Fixture f = newFixture(true, 2, 150);
        when(jobListingRepository.findDetailQueue(2)).thenReturn(List.of(job(1), job(2)));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));

        assertThat(f.service.fetchForRun(RUN_ID, () -> false)).isEqualTo("ok");
        verify(jobListingRepository).findDetailQueue(2);
    }

    @Test
    void zeroCapMeansTheWholeQueueIsRequested() {
        Fixture f = newFixture(true, 0, 150);
        when(jobListingRepository.findDetailQueue(Integer.MAX_VALUE)).thenReturn(List.of(job(1)));
        f.http.enqueue(new StubHttpResponse(200, DETAIL_BODY, null));

        assertThat(f.service.fetchForRun(RUN_ID, () -> false)).isEqualTo("ok");
        verify(jobListingRepository).applyDetail(eq(1L), any(), any(), any(), any());
    }

    @Test
    void aDisabledPhaseMakesNoRequestsAndReadsNothing() {
        Fixture f = newFixture(false, 60, 150);

        assertThat(f.service.fetchForRun(RUN_ID, () -> false)).isEqualTo("ok");

        verify(jobListingRepository, never()).findDetailQueue(anyInt());
        assertThat(f.http.requestsSeen()).isEmpty();
    }

    @Test
    void cancellationIsHonouredBeforeTheFirstRequest() {
        Fixture f = newFixture(true, 60, 150);
        when(jobListingRepository.findDetailQueue(60)).thenReturn(List.of(job(1)));

        assertThat(f.service.fetchForRun(RUN_ID, () -> true)).isEqualTo("cancelled");
        assertThat(f.http.requestsSeen()).isEmpty();
    }
}
