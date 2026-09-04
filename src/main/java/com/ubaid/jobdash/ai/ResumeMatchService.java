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
        return scanInternal(jobListingRepository.findScannableByRun(runId), resumeId, runId, cancelled);
    }

    /** Re-scans a specific set of jobs (e.g. after a filter or resume change) against {@code resumeId}. */
    public ScanResult rescan(List<Long> jobIds, long resumeId, BooleanSupplier cancelled) {
        return scanInternal(jobListingRepository.findScannableByIds(jobIds), resumeId, null, cancelled);
    }

    private ScanResult scanInternal(List<JobListing> scannable, long resumeId, Long runId, BooleanSupplier cancelled) {
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
                return ScanResult.empty();
            }

            List<List<JobListing>> batches = partition(toScan, properties.batchSize());
            return runBatches(batches, resume.contentText(), resumeId, runId, cancelled);
        } catch (Exception e) {
            log.warn("resume match scan failed for resume {}: {}", resumeId, e.toString());
            return new ScanResult(0, 0, 0, 0, 0, 0.0, "Resume match scan failed: " + e.getMessage());
        }
    }

    /** Runs the batches up to {@code ai.concurrency} at a time, on a bounded pool of virtual threads. */
    private ScanResult runBatches(List<List<JobListing>> batches, String resumeText, long resumeId, Long runId,
                                   BooleanSupplier cancelled) {
        int recommended = 0;
        int notRecommended = 0;
        int skipped = 0;
        int failedBatches = 0;
        double totalCostUsd = 0.0;
        String errorMessage = null;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore permits = new Semaphore(Math.max(1, properties.concurrency()));
        try {
            List<Future<BatchOutcome>> futures = new ArrayList<>();
            for (List<JobListing> batch : batches) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                futures.add(executor.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new BatchOutcome(List.of(), 0.0, false, null);
                    }
                    try {
                        return runOneBatch(batch, resumeText, resumeId, runId);
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

        return new ScanResult(recommended + notRecommended, recommended, notRecommended, skipped, failedBatches,
                totalCostUsd, errorMessage);
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
