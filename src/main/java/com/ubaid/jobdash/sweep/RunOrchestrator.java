package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ResumeMatchService;
import com.ubaid.jobdash.source.location.LocationClassifier;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single entry point {@code RunController} calls to start a run. Every run, whatever its
 * source mix, is one {@code sweep_run} row driven through one pipeline on one virtual thread:
 * <pre>
 *   1. LinkedIn collection   SweepService.collect          (if "linkedin" is selected)
 *   2. ATS collection        AtsSweepService.run           (if any board is selected)
 *   3. LinkedIn descriptions DetailFetchService.fetchForRun (if 1 ran and ended "ok")
 *   4. Location judgement    LocationClassifier            (if 2 ran and ended "ok", and usOnly)
 *   5. AI resume scan        ResumeMatchService.scan       (unless cancelled)
 * </pre>
 * Phases 1 and 2 are independent - LinkedIn's budget, breaker and shard loop live entirely in
 * {@link SweepService}, the boards' in {@link AtsSweepService} - so one stopping early never
 * prevents the other from running. Only a cancellation stops everything. The run's terminal
 * status is the first non-"ok" outcome among the phases that ran, in the order above, so a user
 * sees why part of the run was cut short; the scan still runs over whatever was collected.
 */
@Service
public class RunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);

    private final SweepService sweepService;
    private final AtsSweepService atsSweepService;
    private final DetailFetchService detailFetchService;
    private final SweepRunRepository sweepRunRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeMatchService resumeMatchService;
    private final LocationClassifier locationClassifier;
    private final RunProgressRegistry progressRegistry;
    private final Clock clock;

    public RunOrchestrator(SweepService sweepService, AtsSweepService atsSweepService,
                            DetailFetchService detailFetchService,
                            SweepRunRepository sweepRunRepository, ResumeRepository resumeRepository,
                            ResumeMatchService resumeMatchService, LocationClassifier locationClassifier,
                            RunProgressRegistry progressRegistry, Clock clock) {
        this.sweepService = sweepService;
        this.atsSweepService = atsSweepService;
        this.detailFetchService = detailFetchService;
        this.sweepRunRepository = sweepRunRepository;
        this.resumeRepository = resumeRepository;
        this.resumeMatchService = resumeMatchService;
        this.locationClassifier = locationClassifier;
        this.progressRegistry = progressRegistry;
        this.clock = clock;
    }

    /**
     * Starts a run and returns its id immediately; the actual work happens asynchronously on a
     * virtual thread. See the class javadoc for the pipeline.
     *
     * @param sweepRequest the LinkedIn-shaped parameters (keywords/location/hours/sharding/etc.);
     *                     only keywords/location/hours are consulted for the ATS boards.
     * @param sources      the validated, non-empty source selection, e.g. {@code ["linkedin"]},
     *                     {@code ["greenhouse", "lever"]} or {@code ["linkedin", "workday"]}.
     * @param resumeId     an explicit resume to scan against, or null to fall back to the default
     *                     resume (or skip the scan entirely if there is no resume at all).
     */
    public long startRun(SweepRunRequest sweepRequest, List<String> sources, Long resumeId, boolean usOnly) {
        String sourcesText = String.join(",", sources);
        long runId;
        if (sources.contains("linkedin")) {
            // SweepService knows how to derive the row's location/page-cap from a LinkedIn request
            // (blank location while sharding, test-mode cap); reuse that rather than repeat it.
            runId = sweepService.createRun(sweepRequest, sourcesText, resumeId);
        } else {
            runId = sweepRunRepository.create(clock.instant(), sweepRequest.keywords(), sweepRequest.location(),
                    sweepRequest.hours(), sweepRequest.testMode(), sweepRequest.pageCap(), sourcesText, resumeId);
            progressRegistry.start(runId, new SweepProgress(runId, "running", null, 0, 0, 0, 0, false,
                    0, 0, 0, 0, sourcesText, null));
        }

        Thread thread = Thread.ofVirtual().name("run-" + runId)
                .unstarted(() -> executeRun(runId, sweepRequest, sources, resumeId, usOnly));
        progressRegistry.registerThread(runId, thread);
        thread.start();
        return runId;
    }

    private void executeRun(long runId, SweepRunRequest sweepRequest, List<String> sources, Long resumeId,
                             boolean usOnly) {
        progressRegistry.registerCurrentThreadIfAbsent(runId);
        AtomicBoolean cancelFlag = progressRegistry.cancelFlag(runId);
        List<String> atsNames = sources.stream().filter(s -> !"linkedin".equals(s)).toList();
        boolean withLinkedIn = sources.contains("linkedin");
        boolean withAts = !atsNames.isEmpty();

        String linkedInStatus = null;
        String atsStatus = null;
        String detailStatus = null;
        String status = "failed";
        try {
            if (withLinkedIn) {
                linkedInStatus = sweepService.collect(runId, sweepRequest);
            }
            if (withAts && !cancelledNow(cancelFlag)) {
                AtsRunRequest atsRequest = new AtsRunRequest(sweepRequest.keywords(), sweepRequest.location(),
                        sweepRequest.hours(), atsNames, usOnly);
                atsStatus = atsSweepService.run(runId, atsRequest, () -> cancelledNow(cancelFlag));
                // A mixed run with no boards enabled still collected LinkedIn; "no_sources" is
                // only the run's verdict when the boards were all it had.
                if ("no_sources".equals(atsStatus) && withLinkedIn) {
                    log.info("run {}: no ATS companies enabled; continuing with LinkedIn results only", runId);
                    atsStatus = "ok";
                }
            }
            // Only a clean LinkedIn collection is followed by the detail phase. A collection that
            // ended "capped" or "budget_exhausted" has no budget left for details anyway; "blocked"
            // means the breaker is open; "failed" means the results were junk.
            if ("ok".equals(linkedInStatus) && !cancelledNow(cancelFlag)) {
                detailStatus = fetchDetailsSafely(runId, cancelFlag);
            }
            if ("ok".equals(atsStatus) && !cancelledNow(cancelFlag)) {
                classifyLocations(runId, usOnly, cancelFlag);
            }
            // Scan whatever now carries a description, even if a phase stopped early: a budget
            // refusal partway through the detail phase still leaves real descriptions to read,
            // and a blocked LinkedIn phase says nothing about the boards. Only a cancellation
            // skips it.
            if (!cancelledNow(cancelFlag)) {
                runResumeScanIfPossible(runId, sources, resumeId, cancelFlag, "ok");
            }
            status = terminalStatus(cancelledNow(cancelFlag), linkedInStatus, atsStatus, detailStatus);
        } finally {
            finishRun(runId, status, sources);
        }
    }

    /** The first non-"ok" outcome among the phases that ran, in pipeline order; "ok" if all were. */
    static String terminalStatus(boolean cancelled, String linkedInStatus, String atsStatus, String detailStatus) {
        if (cancelled) {
            return "cancelled";
        }
        for (String phase : new String[] {linkedInStatus, atsStatus, detailStatus}) {
            if (phase != null && !"ok".equals(phase)) {
                return phase;
            }
        }
        return "ok";
    }

    /**
     * {@link DetailFetchService} handles every per-row problem itself; this guards against the
     * unexpected, because a detail-phase bug must never turn a successful collection into a run
     * that never finishes.
     */
    private String fetchDetailsSafely(long runId, AtomicBoolean cancelFlag) {
        try {
            return detailFetchService.fetchForRun(runId, () -> cancelledNow(cancelFlag));
        } catch (Exception e) {
            log.warn("detail fetch for run {} threw unexpectedly (DetailFetchService should never throw): {}",
                    runId, e.toString());
            return cancelledNow(cancelFlag) ? "cancelled" : "ok";
        }
    }

    /** Persists the terminal status, publishes the final snapshot under it, and releases the run's bookkeeping. */
    private void finishRun(long runId, String status, List<String> sources) {
        sweepRunRepository.finish(runId, clock.instant(), status);
        SweepProgress finalProgress = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, 0, 0, String.join(",", sources), null));
        progressRegistry.publish(runId, finalProgress.withStatus(status));
        progressRegistry.finish(runId);
    }

    /**
     * Asks Claude which of this run's locations are in the United States, so non-US postings can
     * be hidden. Runs AFTER collection so the whole run costs one batched call rather than one per
     * company, and BEFORE the scan so a foreign posting is never scored.
     *
     * <p>Skipped entirely when the run opted out of US-only, leaving every row unclassified and
     * therefore visible. {@link LocationClassifier} already guarantees it never throws; this still
     * wraps the call, because a classification failure must never turn a successful collection run
     * into a failed one — the same rule the AI scan follows below.
     */
    private void classifyLocations(long runId, boolean usOnly, AtomicBoolean cancelFlag) {
        if (!usOnly) {
            return;
        }
        try {
            locationClassifier.classifyRun(runId, () -> cancelledNow(cancelFlag));
        } catch (Exception e) {
            log.warn("location classification for run {} threw unexpectedly "
                    + "(LocationClassifier should never throw): {}", runId, e.toString());
        }
    }

    /**
     * Resolves the resume to scan against (explicit id, else the default, else "no resume at
     * all") and runs the AI scan. A missing resume skips the scan silently - it must never fail a
     * run that successfully collected jobs. {@link ResumeMatchService} already guarantees it
     * never throws, but this still wraps the call: an AI failure must never turn a successful
     * collection run into a failed one.
     */
    private String runResumeScanIfPossible(long runId, List<String> sources, Long resumeId,
                                            AtomicBoolean cancelFlag, String collectionStatus) {
        Long effectiveResumeId = resumeId != null ? resumeId
                : resumeRepository.findDefault().map(Resume::id).orElse(null);
        if (effectiveResumeId == null) {
            return collectionStatus;
        }

        progressRegistry.publish(runId, scanningProgress(runId, sources));
        try {
            // Republish the whole snapshot with each scan update attached, so the existing SSE
            // stream carries live scan numbers without needing a second channel. The listener is
            // called from the scan's worker threads; publish() is on a ConcurrentHashMap, and
            // ResumeMatchService swallows anything this throws.
            resumeMatchService.scan(runId, effectiveResumeId, () -> cancelledNow(cancelFlag),
                    scanProgress -> progressRegistry.progress(runId)
                            .ifPresent(current -> progressRegistry.publish(runId, current.withScan(scanProgress))));
        } catch (Exception e) {
            log.warn("resume scan for run {} threw unexpectedly (ResumeMatchService should never throw): {}",
                    runId, e.toString());
        }
        return collectionStatus;
    }

    private SweepProgress scanningProgress(long runId, List<String> sources) {
        SweepProgress current = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, "scanning", null, 0, 0, 0, 0, false, 0, 0, 0, 0, String.join(",", sources), null));
        return current.withStatus("scanning");
    }

    private static boolean cancelledNow(AtomicBoolean cancelFlag) {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }
}
