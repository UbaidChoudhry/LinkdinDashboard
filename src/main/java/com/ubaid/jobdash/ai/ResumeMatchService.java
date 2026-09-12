package com.ubaid.jobdash.ai;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Scans a resume against a set of jobs using the local Claude CLI ({@link ClaudeCliClient}),
 * caching every verdict in {@code ai_match} so a re-scan only ever pays for jobs that haven't
 * been scored against that resume yet.
 * <p>
 * Like {@code salary.SalaryEnrichmentService}, this service is best-effort by design and
 * <b>must never throw</b>: a failing batch is logged and skipped, a missing CLI stops the scan
 * and reports why, and a disabled feature or missing resume is a no-op empty result. An AI
 * failure must never abort the run (or request) that triggered it.
 */
@Service
public class ResumeMatchService {

    private static final Logger log = LoggerFactory.getLogger(ResumeMatchService.class);

    /**
     * Human-readable scan activity, routed to its own {@code logs/ai-scan.log} by
     * logback-spring.xml so it can be followed with {@code tail -f} without the rest of Spring's
     * output on top of it. <b>Never log the prompt or the resume through this</b> - it carries
     * the user's CV. Job titles, counts, timings and verdicts only.
     */
    private static final Logger scanLog = LoggerFactory.getLogger("jobdash.ai.scan");

    /** Sanity bounds on a posting-stated salary the model reports - guards against a hallucinated figure. */
    private static final double MIN_PLAUSIBLE_SALARY = 10_000.0;
    private static final double MAX_PLAUSIBLE_SALARY = 2_000_000.0;

    private final ResumeRepository resumeRepository;
    private final JobListingRepository jobListingRepository;
    private final AiMatchRepository aiMatchRepository;
    private final MatchPromptBuilder promptBuilder;
    private final ClaudeCliClient cliClient;
    private final AiProperties properties;
    private final Clock clock;

    public ResumeMatchService(ResumeRepository resumeRepository, JobListingRepository jobListingRepository,
                               AiMatchRepository aiMatchRepository, MatchPromptBuilder promptBuilder,
                               ClaudeCliClient cliClient, AiProperties properties, Clock clock) {
        this.resumeRepository = resumeRepository;
        this.jobListingRepository = jobListingRepository;
        this.aiMatchRepository = aiMatchRepository;
        this.promptBuilder = promptBuilder;
        this.cliClient = cliClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** Scans every scannable, not-yet-cached job of run {@code runId} against {@code resumeId}. */
    public ScanResult scan(long runId, long resumeId, BooleanSupplier cancelled) {
        return scan(runId, resumeId, cancelled, ScanProgressListener.NONE);
    }

    /** As {@link #scan(long, long, BooleanSupplier)}, reporting live progress to {@code listener}. */
    public ScanResult scan(long runId, long resumeId, BooleanSupplier cancelled, ScanProgressListener listener) {
        return scanInternal(jobListingRepository.findScannableByRun(runId), resumeId, runId, cancelled, listener);
    }

    /** Re-scans a specific set of jobs (e.g. after a filter or resume change) against {@code resumeId}. */
    public ScanResult rescan(List<Long> jobIds, long resumeId, BooleanSupplier cancelled) {
        return rescan(jobIds, resumeId, cancelled, ScanProgressListener.NONE);
    }

    /** As {@link #rescan(List, long, BooleanSupplier)}, reporting live progress to {@code listener}. */
    public ScanResult rescan(List<Long> jobIds, long resumeId, BooleanSupplier cancelled,
                              ScanProgressListener listener) {
        return scanInternal(jobListingRepository.findScannableByIds(jobIds), resumeId, null, cancelled, listener);
    }

    private ScanResult scanInternal(List<JobListing> scannable, long resumeId, Long runId,
                                     BooleanSupplier cancelled, ScanProgressListener listener) {
        try {
            if (!properties.enabled()) {
                return ScanResult.empty();
            }
            Resume resume = resumeRepository.findById(resumeId).orElse(null);
            if (resume == null) {
                return ScanResult.empty();
            }

            Set<Long> alreadyScanned = aiMatchRepository.findScannedJobIds(
                    scannable.stream().map(JobListing::jobId).toList(), resumeId);
            List<JobListing> toScan = scannable.stream()
                    .filter(j -> !alreadyScanned.contains(j.jobId()))
                    .toList();

            if (toScan.isEmpty()) {
                scanLog.info("scan run={} resume=\"{}\" - nothing to do, all {} job(s) already scored",
                        runId, resume.name(), scannable.size());
                return ScanResult.empty();
            }

            List<List<JobListing>> batches = partition(toScan, properties.batchSize());
            scanLog.info("scan START run={} resume=\"{}\" scannable={} cached={} to-scan={} batches={} "
                            + "batch-size={} concurrency={} model={}",
                    runId, resume.name(), scannable.size(), alreadyScanned.size(), toScan.size(),
                    batches.size(), properties.batchSize(), properties.concurrency(), properties.model());
            return runBatches(batches, resume.contentText(), resumeId, runId, cancelled, listener);
        } catch (Exception e) {
            log.warn("resume match scan failed for resume {}: {}", resumeId, e.toString());
            return new ScanResult(0, 0, 0, 0, 0, 0.0, "Resume match scan failed: " + e.getMessage());
        }
    }

    /** Runs the batches up to {@code ai.concurrency} at a time, on a bounded pool of virtual threads. */
    private ScanResult runBatches(List<List<JobListing>> batches, String resumeText, long resumeId, Long runId,
                                   BooleanSupplier cancelled, ScanProgressListener listener) {
        int recommended = 0;
        int notRecommended = 0;
        int skipped = 0;
        int failedBatches = 0;
        double totalCostUsd = 0.0;
        String errorMessage = null;

        int jobsTotal = batches.stream().mapToInt(List::size).sum();
        Instant scanStartedAt = clock.instant();
        ScanCounters counters = new ScanCounters(batches.size(), jobsTotal, scanStartedAt);
        publishQuietly(listener, counters.snapshot());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore permits = new Semaphore(Math.max(1, properties.concurrency()));
        try {
            List<Future<BatchOutcome>> futures = new ArrayList<>();
            int batchNumber = 0;
            for (List<JobListing> batch : batches) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                int number = ++batchNumber;
                futures.add(executor.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new BatchOutcome(List.of(), 0.0, false, null);
                    }
                    try {
                        scanLog.info("batch {}/{} START ({} jobs)", number, batches.size(), batch.size());
                        long startedAt = System.nanoTime();
                        BatchOutcome outcome = runOneBatch(batch, resumeText, resumeId, runId);
                        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
                        // Counted here, as this batch actually finishes, rather than in the drain
                        // loop below - that loop reads futures in submission order, so a fast
                        // later batch would stay invisible behind a slow earlier one.
                        recordBatch(counters, listener, number, batches.size(), batch.size(), outcome, tookMs);
                        return outcome;
                    } finally {
                        permits.release();
                    }
                }));
            }

            for (Future<BatchOutcome> future : futures) {
                BatchOutcome outcome;
                try {
                    outcome = future.get();
                } catch (Exception e) {
                    log.warn("resume match batch failed: {}", e.toString());
                    failedBatches++;
                    continue;
                }

                if (outcome.cliNotFound()) {
                    errorMessage = outcome.errorMessage();
                    // Stop consuming further results; retrying against a missing binary is pointless.
                    break;
                }
                if (outcome.matches().isEmpty() && outcome.errorMessage() != null) {
                    failedBatches++;
                    continue;
                }

                totalCostUsd += outcome.costUsd();
                if (!outcome.matches().isEmpty()) {
                    aiMatchRepository.upsertAll(outcome.matches());
                    for (AiMatch m : outcome.matches()) {
                        if (m.recommended()) {
                            recommended++;
                        } else {
                            notRecommended++;
                        }
                    }
                }
            }
        } finally {
            executor.shutdown();
        }

        for (List<JobListing> batch : batches) {
            skipped += batch.size();
        }
        skipped -= recommended + notRecommended;
        if (skipped < 0) {
            skipped = 0;
        }

        ScanResult result = new ScanResult(recommended + notRecommended, recommended, notRecommended, skipped,
                failedBatches, totalCostUsd, errorMessage);
        scanLog.info("scan DONE  run={} scanned={} recommended={} not-recommended={} skipped={} failed-batches={} "
                        + "cost=${} elapsed={}s{}",
                runId, result.scanned(), result.recommended(), result.notRecommended(), result.skipped(),
                result.failedBatches(), String.format("%.4f", result.totalCostUsd()),
                Duration.between(scanStartedAt, clock.instant()).toSeconds(),
                result.errorMessage() == null ? "" : " error=" + result.errorMessage());
        return result;
    }

    /** Updates the shared counters for one finished batch, logs it, and publishes a snapshot. */
    private void recordBatch(ScanCounters counters, ScanProgressListener listener, int number, int total,
                              int batchSize, BatchOutcome outcome, long tookMs) {
        int recommended = 0;
        int notRecommended = 0;
        for (AiMatch match : outcome.matches()) {
            if (match.recommended()) {
                recommended++;
            } else {
                notRecommended++;
            }
        }
        boolean failed = outcome.matches().isEmpty() && outcome.errorMessage() != null;
        counters.record(recommended, notRecommended, outcome.costUsd(), failed);

        if (failed) {
            scanLog.warn("batch {}/{} FAILED after {}ms - {}", number, total, tookMs, outcome.errorMessage());
        } else {
            scanLog.info("batch {}/{} done  {}ms  cost=${}  recommended={} not-recommended={}{}",
                    number, total, tookMs, String.format("%.4f", outcome.costUsd()), recommended, notRecommended,
                    recommended + notRecommended < batchSize
                            ? "  (" + (batchSize - recommended - notRecommended) + " job(s) got no verdict)"
                            : "");
        }
        publishQuietly(listener, counters.snapshot());
    }

    /**
     * A listener is UI plumbing, not part of the scan. If one throws, that must not be able to
     * take down a batch that otherwise succeeded.
     */
    private static void publishQuietly(ScanProgressListener listener, ScanProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException e) {
            log.debug("scan progress listener threw: {}", e.toString());
        }
    }

    /** Thread-safe tallies shared by the in-flight batches. */
    private static final class ScanCounters {
        private final int batchesTotal;
        private final int jobsTotal;
        private final Instant startedAt;
        private final AtomicInteger batchesDone = new AtomicInteger();
        private final AtomicInteger recommended = new AtomicInteger();
        private final AtomicInteger notRecommended = new AtomicInteger();
        private final AtomicInteger failedBatches = new AtomicInteger();
        private final AtomicLong costMicros = new AtomicLong();

        ScanCounters(int batchesTotal, int jobsTotal, Instant startedAt) {
            this.batchesTotal = batchesTotal;
            this.jobsTotal = jobsTotal;
            this.startedAt = startedAt;
        }

        void record(int recommendedDelta, int notRecommendedDelta, double costUsd, boolean failed) {
            batchesDone.incrementAndGet();
            recommended.addAndGet(recommendedDelta);
            notRecommended.addAndGet(notRecommendedDelta);
            if (failed) {
                failedBatches.incrementAndGet();
            }
            // Accumulated in integer micros: repeatedly adding doubles from several threads would
            // drift, and there is no atomic double add that is also cheap to read consistently.
            costMicros.addAndGet(Math.round(costUsd * 1_000_000d));
        }

        ScanProgress snapshot() {
            int rec = recommended.get();
            int notRec = notRecommended.get();
            return new ScanProgress(batchesDone.get(), batchesTotal, rec + notRec, jobsTotal, rec, notRec,
                    failedBatches.get(), costMicros.get() / 1_000_000d, startedAt);
        }
    }

    /** Runs one batch against the CLI and correlates the returned verdicts back to jobs by ref (the job id). */
    private BatchOutcome runOneBatch(List<JobListing> batch, String resumeText, long resumeId, Long runId) {
        try {
            Map<String, JobListing> byRef = new HashMap<>();
            List<JobForMatching> forMatching = new ArrayList<>();
            for (JobListing job : batch) {
                String ref = String.valueOf(job.jobId());
                byRef.put(ref, job);
                forMatching.add(new JobForMatching(ref, job.title(), job.company(), job.location(), job.description()));
            }

            String prompt = promptBuilder.build(resumeText, forMatching);
            ClaudeCliResult result = cliClient.run(prompt);

            return switch (result) {
                case ClaudeCliResult.Ok ok -> {
                    Instant now = clock.instant();
                    List<AiMatch> matches = new ArrayList<>();
                    for (MatchVerdict verdict : ok.verdicts()) {
                        JobListing job = byRef.get(verdict.ref());
                        if (job == null) {
                            // A ref that matches no job in this batch is ignored - nothing to correlate it to.
                            continue;
                        }
                        matches.add(new AiMatch(job.jobId(), resumeId, verdict.recommended(), verdict.reason(),
                                properties.model(), runId, now));
                        applyPostingSalaryIfValid(job.jobId(), verdict.salaryMin(), verdict.salaryMax());
                    }
                    yield new BatchOutcome(matches, ok.costUsd(), false, null);
                }
                case ClaudeCliResult.CliNotFound notFound ->
                        new BatchOutcome(List.of(), 0.0, true, notFound.message());
                case ClaudeCliResult.Timeout timeout ->
                        new BatchOutcome(List.of(), 0.0, false, "claude CLI timed out after " + timeout.afterMs() + "ms");
                case ClaudeCliResult.Failed failed ->
                        new BatchOutcome(List.of(), 0.0, false, failed.message());
            };
        } catch (RuntimeException e) {
            log.warn("resume match batch threw: {}", e.toString());
            return new BatchOutcome(List.of(), 0.0, false, e.getMessage());
        }
    }

    /**
     * Writes a posting-stated salary onto the job when the model reported one and it survives
     * sanity bounds. The model can hallucinate, so a verdict is rejected (logged, row untouched)
     * rather than trusted blindly when it's inverted or implausible - this is the only guard
     * between an LLM's output and a number shown to the user as ground truth.
     */
    private void applyPostingSalaryIfValid(long jobId, Integer salaryMin, Integer salaryMax) {
        if (salaryMin == null || salaryMax == null) {
            return;
        }
        if (salaryMin > salaryMax) {
            log.debug("rejecting posting salary for job {}: min {} > max {}", jobId, salaryMin, salaryMax);
            return;
        }
        if (salaryMin < MIN_PLAUSIBLE_SALARY || salaryMax > MAX_PLAUSIBLE_SALARY) {
            log.debug("rejecting posting salary for job {}: {}-{} outside plausible range", jobId, salaryMin, salaryMax);
            return;
        }
        jobListingRepository.applyPostingSalary(jobId, salaryMin, salaryMax);
    }

    private static List<List<JobListing>> partition(List<JobListing> jobs, int batchSize) {
        int size = Math.max(1, batchSize);
        List<List<JobListing>> batches = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i += size) {
            batches.add(jobs.subList(i, Math.min(i + size, jobs.size())));
        }
        return batches;
    }

    /** One batch's outcome: either persistable matches, or a reason nothing was persisted. */
    private record BatchOutcome(List<AiMatch> matches, double costUsd, boolean cliNotFound, String errorMessage) {
    }

    /**
     * Summary of one scan. {@code scanned} is the count of jobs that actually got a verdict;
     * {@code skipped} counts jobs sent to a batch that never returned a verdict for them (a
     * failed/timed-out batch, or a ref the CLI omitted); {@code errorMessage} is non-null only
     * when the scan stopped early because the CLI binary could not be found.
     */
    public record ScanResult(int scanned, int recommended, int notRecommended, int skipped, int failedBatches,
                              double totalCostUsd, String errorMessage) {
        public static ScanResult empty() {
            return new ScanResult(0, 0, 0, 0, 0, 0.0, null);
        }
    }
}
