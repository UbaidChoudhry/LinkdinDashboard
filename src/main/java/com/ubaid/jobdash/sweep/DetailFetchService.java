package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.http.FetchResult;
import com.ubaid.jobdash.http.PacedHttpClient;
import com.ubaid.jobdash.http.SweepProperties;
import com.ubaid.jobdash.source.search.DetailParser;
import com.ubaid.jobdash.source.search.JobDetail;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The detail-fetch phase of a LinkedIn run: after collection, reads the guest job-detail fragment
 * for the run's newest passing rows so they carry a description for the AI scan. LinkedIn's search
 * cards have none (HANDOFF.md §2); the fragment is the only anonymous source of one.
 * <p>
 * Every request goes through {@link PacedHttpClient#fetchDetail}, so it is paced, counted against
 * the same per-run cap and rolling 24h budget as the search pages, and refused while the breaker
 * is open. The queue is global (every unfetched passing LinkedIn row, newest first), so rows a
 * previous run could not reach are drained by the next; {@code sweep.detail.max-per-run} can
 * bound one run's spend, and 0 (the default) means only the shared budget bounds it. The
 * three-outcome rule from HANDOFF.md §5 is applied per row:
 * <ul>
 *     <li>{@code OK} → description + hash stored, {@code detail_status = 'ok'}, never re-fetched;</li>
 *     <li>{@code GONE} (404) → {@code detail_status = 'gone'}, never re-fetched;</li>
 *     <li>{@code BLOCKED}, a budget refusal, or the breaker → the row is left untouched so the
 *     next run retries it, and the phase stops.</li>
 * </ul>
 * Publishes into the shared {@link RunProgressRegistry} under status {@code "fetching_details"},
 * like {@link SweepService} and {@link AtsSweepService} do for their phases.
 */
@Service
public class DetailFetchService {

    private static final Logger log = LoggerFactory.getLogger(DetailFetchService.class);

    private final PacedHttpClient pacedHttpClient;
    private final DetailParser detailParser;
    private final JobListingRepository jobListingRepository;
    private final SweepRunRepository sweepRunRepository;
    private final RunProgressRegistry progressRegistry;
    private final SweepProperties sweepProperties;
    private final Clock clock;

    public DetailFetchService(PacedHttpClient pacedHttpClient, DetailParser detailParser,
                               JobListingRepository jobListingRepository, SweepRunRepository sweepRunRepository,
                               RunProgressRegistry progressRegistry, SweepProperties sweepProperties, Clock clock) {
        this.pacedHttpClient = pacedHttpClient;
        this.detailParser = detailParser;
        this.jobListingRepository = jobListingRepository;
        this.sweepRunRepository = sweepRunRepository;
        this.progressRegistry = progressRegistry;
        this.sweepProperties = sweepProperties;
        this.clock = clock;
    }

    /**
     * Runs the phase synchronously on the calling thread and returns its terminal status:
     * {@code "ok"} when the queue was drained (or the phase is disabled / had nothing to do),
     * else the same vocabulary {@link SweepService} uses - {@code "capped"},
     * {@code "budget_exhausted"}, {@code "blocked"} or {@code "cancelled"}. Never throws for a
     * per-row problem: one transport error or unparseable page skips that row and continues.
     */
    public String fetchForRun(long runId, BooleanSupplier cancelled) {
        SweepProperties.Detail settings = sweepProperties.detail();
        if (settings == null || !settings.enabled()) {
            return "ok";
        }
        int cap = settings.maxPerRun() > 0 ? settings.maxPerRun() : Integer.MAX_VALUE;

        List<JobListing> queue = jobListingRepository.findDetailQueue(cap);
        int total = queue.size();
        int done = 0;
        int requestsMade = progressRegistry.progress(runId).map(SweepProgress::requestsMade).orElse(0);
        publish(runId, "fetching_details", done, total, requestsMade);
        if (total == 0) {
            return "ok";
        }
        log.info("run {}: fetching {} job detail(s){}", runId, total,
                cap == Integer.MAX_VALUE ? "" : " (cap " + cap + ")");

        for (JobListing job : queue) {
            if (cancelled.getAsBoolean()) {
                return "cancelled";
            }

            URI uri = SweepQueryBuilder.buildDetailUri(job.sourceJobId());
            FetchResult result = pacedHttpClient.fetchDetail(uri);

            // Exhaustive over the sealed FetchResult, so a new refusal kind can't fall through.
            switch (result) {
                case FetchResult.BlockedByRunCap blocked -> {
                    log.info("run {}: detail phase stopped by the per-run cap after {}/{}", runId, done, total);
                    return "capped";
                }
                case FetchResult.BlockedByDailyBudget blocked -> {
                    log.info("run {}: detail phase stopped by the daily budget after {}/{}", runId, done, total);
                    return "budget_exhausted";
                }
                case FetchResult.BlockedByCircuit blocked -> {
                    log.info("run {}: detail phase stopped by the circuit breaker after {}/{}", runId, done, total);
                    return "blocked";
                }
                case FetchResult.TransportError error -> {
                    // One unreachable fragment must not end the phase; the row stays queued for
                    // next time and the breaker has already counted the soft failure.
                    requestsMade++;
                    log.warn("run {}: detail fetch for job {} failed in transport: {}", runId,
                            job.sourceJobId(), error.message());
                    publish(runId, "fetching_details", done, total, requestsMade);
                    if (cancelled.getAsBoolean()) {
                        return "cancelled";
                    }
                }
                case FetchResult.Completed completed -> {
                    requestsMade++;
                    switch (completed.outcome()) {
                        case OK -> {
                            Optional<JobDetail> detail = detailParser.parse(completed.body());
                            if (detail.isPresent()) {
                                jobListingRepository.applyDetail(job.jobId(), detail.get().description(),
                                        detail.get().descriptionHash(), detail.get().applyKind(), clock.instant());
                                done++;
                            } else {
                                // Can't happen - OK means the client's own parse succeeded - but if the
                                // two parses ever disagree, leave the row queued rather than store nothing.
                                log.warn("run {}: job {} classified OK but yielded no description", runId,
                                        job.sourceJobId());
                            }
                        }
                        case GONE -> {
                            jobListingRepository.markDetailGone(job.jobId(), clock.instant());
                            done++;
                        }
                        case BLOCKED -> {
                            if (completed.statusCode() == 429 || completed.statusCode() == 999) {
                                log.warn("run {}: detail fetch blocked (HTTP {}) after {}/{}", runId,
                                        completed.statusCode(), done, total);
                                publish(runId, "fetching_details", done, total, requestsMade);
                                return "blocked";
                            }
                            // A 200 with no description, an empty body, or a 5xx: skip this row (it
                            // stays queued) and keep going. If it is systemic, the breaker's soft-
                            // failure count trips it and the next fetch is refused above.
                            log.warn("run {}: job {} returned an unusable detail response (HTTP {}); skipping",
                                    runId, job.sourceJobId(), completed.statusCode());
                        }
                        case END_OF_RESULTS, PAST_CAP, IRRELEVANT -> {
                            // Search-page outcomes; classifyDetail never produces them.
                            log.warn("run {}: unexpected detail outcome {} for job {}", runId,
                                    completed.outcome(), job.sourceJobId());
                        }
                    }
                    publish(runId, "fetching_details", done, total, requestsMade);
                }
            }
        }

        log.info("run {}: detail phase complete, {}/{} fetched", runId, done, total);
        return "ok";
    }

    private void publish(long runId, String status, int done, int total, int requestsMade) {
        sweepRunRepository.updateDetailProgress(runId, done, total, requestsMade);
        SweepProgress current = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, 0, 0, "linkedin", null));
        progressRegistry.publish(runId, current.withDetails(status, done, total, requestsMade));
    }
}
