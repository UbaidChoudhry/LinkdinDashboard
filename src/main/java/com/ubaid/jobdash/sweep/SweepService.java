package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.http.FetchResult;
import com.ubaid.jobdash.http.PacedHttpClient;
import com.ubaid.jobdash.http.SweepProperties;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.salary.SalaryEnrichmentService;
import com.ubaid.jobdash.source.search.CardParser;
import com.ubaid.jobdash.source.search.JobCard;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a LinkedIn guest job-search sweep: builds query URLs, paginates through results
 * via {@link PacedHttpClient} (which owns rate limiting and circuit breaking), parses and stores
 * new cards, tracks progress, and supports cooperative cancellation.
 * <p>
 * Deliberately has no controller and no SSE endpoint. This class only exposes plain methods:
 * {@link #createRun} + {@link #collect} for {@link RunOrchestrator} (which chains the detail-fetch
 * and AI-scan phases after collection on its own thread), {@link #startRun} / {@link #run} as the
 * self-contained collect-and-finish form tests drive deterministically, {@link #progress} to read
 * a snapshot, and {@link #cancel} to request cancellation.
 */
@Service
public class SweepService {

    /** Every page is 10 results; LinkedIn's guest search steps offsets in tens, not 25s. */
    private static final int PAGE_SIZE = 10;
    /** The last {@code start} offset LinkedIn's guest search will accept before returning 400. */
    private static final int LAST_START_BEFORE_CAP = 990;

    private final PacedHttpClient pacedHttpClient;
    private final CardParser cardParser;
    private final FilterEngine filterEngine;
    private final JobListingRepository jobListingRepository;
    private final SweepRunRepository sweepRunRepository;
    private final SweepShardsProperties shardsProperties;
    private final SweepProperties sweepProperties;
    private final SalaryEnrichmentService salaryEnrichmentService;
    private final RunProgressRegistry progressRegistry;
    private final Clock clock;

    public SweepService(PacedHttpClient pacedHttpClient, CardParser cardParser, FilterEngine filterEngine,
                         JobListingRepository jobListingRepository, SweepRunRepository sweepRunRepository,
                         SweepShardsProperties shardsProperties, SweepProperties sweepProperties,
                         SalaryEnrichmentService salaryEnrichmentService, RunProgressRegistry progressRegistry,
                         Clock clock) {
        this.pacedHttpClient = pacedHttpClient;
        this.cardParser = cardParser;
        this.filterEngine = filterEngine;
        this.jobListingRepository = jobListingRepository;
        this.sweepRunRepository = sweepRunRepository;
        this.shardsProperties = shardsProperties;
        this.sweepProperties = sweepProperties;
        this.salaryEnrichmentService = salaryEnrichmentService;
        this.progressRegistry = progressRegistry;
        this.clock = clock;
    }

    /**
     * Creates the {@code sweep_run} row and starts the pagination loop on a new virtual thread,
     * returning the run id immediately. Use {@link #progress(long)} to poll status and
     * {@link #cancel(long)} to stop it early.
     */
    public long startRun(SweepRunRequest request) {
        long runId = createRun(request, "linkedin", null);
        Thread thread = Thread.ofVirtual().name("sweep-run-" + runId).unstarted(() -> run(runId, request));
        progressRegistry.registerThread(runId, thread);
        thread.start();
        return runId;
    }

    /** Reads the current in-memory progress snapshot for a run, if it's known to this process. */
    public Optional<SweepProgress> progress(long runId) {
        return progressRegistry.progress(runId);
    }

    /**
     * Requests cooperative cancellation of an in-flight run: sets a flag the loop checks at the
     * top of every iteration, and interrupts the run's thread (if known to this process) so a
     * blocked pacing wait unblocks promptly instead of waiting out its full delay.
     *
     * @return true if a cancellation was actually requested (the run was known and not already
     * finished); false otherwise.
     */
    public boolean cancel(long runId) {
        return progressRegistry.cancel(runId);
    }

    /**
     * Creates the {@code sweep_run} row (with its source list, e.g. {@code "linkedin"} or
     * {@code "linkedin,greenhouse"}, and optionally the resume a later AI scan will use) and
     * registers its initial progress snapshot, without starting any work. {@link RunOrchestrator}
     * pairs this with {@link #collect} on its own thread so it can chain the ATS collection,
     * detail-fetch and scan phases after it.
     */
    public long createRun(SweepRunRequest request, String sources, Long resumeId) {
        Integer pageCap = effectivePageCap(request);
        String location = request.shardingEnabled() ? "" : nullToEmpty(request.location());
        long runId = sweepRunRepository.create(clock.instant(), request.keywords(), location,
                request.hours(), request.testMode(), pageCap, sources, resumeId);
        progressRegistry.start(runId, new SweepProgress(runId, "running", null, 0, 0, 0, 0, false,
                0, 0, 0, 0, sources, null));
        return runId;
    }

    /**
     * Runs the sweep synchronously on the calling thread: one pagination loop per shard (or a
     * single pass against {@code request.location()} when sharding isn't enabled), accumulating
     * counters into one run record. Blocks until the run reaches a terminal status. Public so
     * tests can invoke it directly and inspect the result without dealing with real concurrency;
     * {@link #startRun} is the async entry point production code uses.
     */
    public void run(long runId, SweepRunRequest request) {
        String status = "failed";
        try {
            status = collect(runId, request);
        } finally {
            String finalStatus = status;
            sweepRunRepository.finish(runId, clock.instant(), finalStatus);
            progressRegistry.progress(runId).ifPresent(p -> progressRegistry.publish(runId, p.withStatus(finalStatus)));
            progressRegistry.finish(runId);
        }
    }

    /**
     * The collection phase alone: the shard/pagination loop, publishing {@code "running"} progress
     * as it goes, returning the terminal status it reached ({@code "ok"}, {@code "capped"},
     * {@code "budget_exhausted"}, {@code "blocked"}, {@code "failed"} or {@code "cancelled"})
     * <em>without</em> marking the run finished. The caller decides what follows - {@link #run}
     * finishes immediately; {@link RunOrchestrator} first fetches job details and runs the AI scan.
     */
    public String collect(long runId, SweepRunRequest request) {
        progressRegistry.registerCurrentThreadIfAbsent(runId);
        AtomicBoolean cancelFlag = progressRegistry.cancelFlag(runId);
        // Layer B of the rate limiter is per RUN; without this reset it would count across runs
        // for the life of the process and every run after the first would end "capped" early.
        pacedHttpClient.beginRun();

        List<String> shardsToRun = request.shardingEnabled()
                ? (request.shards() != null && !request.shards().isEmpty() ? request.shards() : shardsProperties.shardsOrDefault())
                : List.of(request.location());

        RunAccumulator acc = new RunAccumulator();
        String status = "ok";

        for (String shard : shardsToRun) {
            boolean sharding = request.shardingEnabled();
            String shardLabel = sharding ? shard : null;
            String location = sharding ? shard : request.location();

            ShardOutcome outcome = runShard(runId, request, location, shardLabel, acc, cancelFlag);
            if (sharding) {
                acc.shardsRun.add(shard);
            }
            if (outcome.terminal() != null) {
                status = outcome.terminal();
                break;
            }
            // outcome.terminal() == null means this shard ended normally (END_OF_RESULTS,
            // PAST_CAP, or the test-mode page cap) - move on to the next shard, if any.
        }
        publishProgress(runId, status, null, acc);
        return status;
    }

    /** Non-null terminal status if the whole run must stop; null if only this shard is done. */
    private record ShardOutcome(String terminal) {
    }

    /**
     * The page cap actually in force for a run: an explicit per-run cap wins, otherwise test
     * mode falls back to the configured default. Null means uncapped.
     */
    private Integer effectivePageCap(SweepRunRequest request) {
        if (request.pageCap() != null) {
            return request.pageCap();
        }
        return request.testMode() ? sweepProperties.budget().testModePageCap() : null;
    }

    private ShardOutcome runShard(long runId, SweepRunRequest request, String location, String shardLabel,
                                   RunAccumulator acc, AtomicBoolean cancelFlag) {
        int start = 0;
        while (true) {
            if (cancelFlag.get() || Thread.currentThread().isInterrupted()) {
                return new ShardOutcome("cancelled");
            }
            Integer pageCap = effectivePageCap(request);
            if (pageCap != null && acc.pagesFetched >= pageCap) {
                return new ShardOutcome(null);
            }

            URI uri = SweepQueryBuilder.buildUri(request.keywords(), location, request.hours(), start);
            FetchResult result = pacedHttpClient.fetch(uri, start, request.keywords());

            if (result instanceof FetchResult.BlockedByRunCap) {
                return new ShardOutcome("capped");
            }
            if (result instanceof FetchResult.BlockedByDailyBudget) {
                return new ShardOutcome("budget_exhausted");
            }
            if (result instanceof FetchResult.BlockedByCircuit) {
                return new ShardOutcome("blocked");
            }
            if (result instanceof FetchResult.TransportError) {
                acc.requestsMade++;
                publishProgress(runId, "running", shardLabel, acc);
                return new ShardOutcome(cancelFlag.get() || Thread.currentThread().isInterrupted() ? "cancelled" : "failed");
            }

            FetchResult.Completed completed = (FetchResult.Completed) result;
            acc.requestsMade++;
            acc.pagesFetched++;

            switch (completed.outcome()) {
                case OK -> {
                    List<JobCard> cards = cardParser.parse(completed.body());
                    acc.cardsSeen += cards.size();
                    if (!cards.isEmpty()) {
                        List<JobCardInsert> inserts = cards.stream().map(SweepService::toInsert).toList();
                        int newCount = jobListingRepository.upsertAll(inserts, runId, clock.instant());
                        acc.jobsNew += newCount;
                        // Stamp a verdict on the rows we just wrote. Filtering belongs to the
                        // sweep, not to a read: doing it here keeps every reader (search tab,
                        // reports, the detail queue's partial index) seeing triaged rows, and
                        // means results streamed mid-run are already filtered.
                        filterEngine.evaluateNewRows();
                        // Salary enrichment is best-effort and runs inline per the product
                        // decision. SalaryEnrichmentService guarantees it never throws, so it
                        // can't abort this pagination loop.
                        salaryEnrichmentService.enrichRun(runId, location,
                                () -> cancelFlag.get() || Thread.currentThread().isInterrupted());
                    }
                    if (start == LAST_START_BEFORE_CAP && cards.size() >= PAGE_SIZE) {
                        // A full page at the last offset before LinkedIn's cap means >=1000
                        // results existed and everything past #1000 is invisible to us.
                        acc.saturated = true;
                    }
                    publishProgress(runId, "running", shardLabel, acc);
                    start += PAGE_SIZE;
                    // continue this shard's loop
                }
                case END_OF_RESULTS -> {
                    publishProgress(runId, "running", shardLabel, acc);
                    return new ShardOutcome(null);
                }
                case PAST_CAP -> {
                    acc.saturated = true;
                    publishProgress(runId, "running", shardLabel, acc);
                    return new ShardOutcome(null);
                }
                case BLOCKED -> {
                    publishProgress(runId, "running", shardLabel, acc);
                    return new ShardOutcome("blocked");
                }
                case IRRELEVANT -> {
                    // Nothing from this page is stored - upsertAll is never called above for it.
                    publishProgress(runId, "running", shardLabel, acc);
                    return new ShardOutcome("failed");
                }
            }
        }
    }

    private void publishProgress(long runId, String status, String currentShard, RunAccumulator acc) {
        String shardsUsed = acc.shardsRun.isEmpty() ? null : String.join(",", acc.shardsRun);
        sweepRunRepository.updateProgress(runId, shardsUsed, acc.pagesFetched, acc.requestsMade,
                acc.cardsSeen, acc.jobsNew, acc.saturated);
        // Build on the current snapshot so a mixed run's other counters (companies, details,
        // the source list) are carried rather than zeroed.
        SweepProgress current = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, 0, 0, "linkedin", null));
        progressRegistry.publish(runId, new SweepProgress(runId, status, currentShard,
                acc.pagesFetched, acc.requestsMade, acc.cardsSeen, acc.jobsNew, acc.saturated,
                current.companiesDone(), current.companiesTotal(), current.detailsDone(), current.detailsTotal(),
                current.sources(), current.scan()));
    }

    private static JobCardInsert toInsert(JobCard card) {
        return new JobCardInsert(
                "linkedin",
                String.valueOf(card.jobId()),
                card.title(),
                card.company(),
                card.location(),
                card.postedDate() == null ? null : card.postedDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                card.jobUrl(),
                card.companyUrl(),
                null
        );
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Mutable per-run counters, accumulated across shards. */
    private static final class RunAccumulator {
        int pagesFetched;
        int requestsMade;
        int cardsSeen;
        int jobsNew;
        boolean saturated;
        final List<String> shardsRun = new ArrayList<>();
    }
}
