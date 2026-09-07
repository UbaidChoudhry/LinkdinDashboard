package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ResumeMatchService;
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
 * The single entry point {@code RunController} calls to start a run of any kind. Dispatches
 * between the two collection paths and, for a successful ATS collection, chains the optional AI
 * resume scan afterwards.
 * <p>
 * <b>LinkedIn-only runs are untouched</b>: when {@code sources} is exactly {@code ["linkedin"]},
 * this delegates straight to {@link SweepService#startRun} - the existing 300/day budget,
 * circuit breaker, response-outcome handling and metro-shard logic are not re-implemented or
 * wrapped here in any way. Any other source selection is a fresh {@code sweep_run} row driven by
 * {@link AtsSweepService} on a virtual thread, optionally followed by
 * {@link ResumeMatchService#scan}.
 */
@Service
public class RunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);

    private final SweepService sweepService;
    private final AtsSweepService atsSweepService;
    private final SweepRunRepository sweepRunRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeMatchService resumeMatchService;
    private final RunProgressRegistry progressRegistry;
    private final Clock clock;

    public RunOrchestrator(SweepService sweepService, AtsSweepService atsSweepService,
                            SweepRunRepository sweepRunRepository, ResumeRepository resumeRepository,
                            ResumeMatchService resumeMatchService, RunProgressRegistry progressRegistry,
                            Clock clock) {
        this.sweepService = sweepService;
        this.atsSweepService = atsSweepService;
        this.sweepRunRepository = sweepRunRepository;
        this.resumeRepository = resumeRepository;
        this.resumeMatchService = resumeMatchService;
        this.progressRegistry = progressRegistry;
        this.clock = clock;
    }

    /**
     * Starts a run and returns its id immediately; the actual work happens asynchronously. See
     * the class javadoc for the LinkedIn-only vs. ATS dispatch.
     *
     * @param sweepRequest the LinkedIn-shaped parameters (keywords/location/hours/sharding/etc.);
     *                     only keywords/location/hours are consulted for an ATS run.
     * @param sources      the validated, non-empty source selection, e.g. {@code ["linkedin"]} or
     *                     {@code ["greenhouse", "lever"]}.
     * @param resumeId     an explicit resume to scan against, or null to fall back to the default
     *                     resume (or skip the scan entirely if there is no resume at all).
     */
    public long startRun(SweepRunRequest sweepRequest, List<String> sources, Long resumeId, boolean usOnly) {
        if (isLinkedInOnly(sources)) {
            return sweepService.startRun(sweepRequest);
        }

        long runId = sweepRunRepository.create(clock.instant(), sweepRequest.keywords(), sweepRequest.location(),
                sweepRequest.hours(), sweepRequest.testMode(), sweepRequest.pageCap(),
                String.join(",", sources), resumeId);
        String sourcesText = String.join(",", sources);
        progressRegistry.start(runId, new SweepProgress(runId, "running", null, 0, 0, 0, 0, false,
                0, 0, sourcesText, null));

        Thread thread = Thread.ofVirtual().name("ats-run-" + runId)
                .unstarted(() -> executeAtsRun(runId, sweepRequest, sources, resumeId, usOnly));
        progressRegistry.registerThread(runId, thread);
        thread.start();
        return runId;
    }

    private static boolean isLinkedInOnly(List<String> sources) {
        return sources.size() == 1 && "linkedin".equals(sources.get(0));
    }

    private void executeAtsRun(long runId, SweepRunRequest sweepRequest, List<String> sources, Long resumeId,
                                boolean usOnly) {
        progressRegistry.registerCurrentThreadIfAbsent(runId);
        AtomicBoolean cancelFlag = progressRegistry.cancelFlag(runId);

        AtsRunRequest atsRequest = new AtsRunRequest(sweepRequest.keywords(), sweepRequest.location(),
                sweepRequest.hours(), sources, usOnly);
        String status = atsSweepService.run(runId, atsRequest, () -> cancelledNow(cancelFlag));

        // Only a clean collection ("ok") is followed by the AI scan - a stop for cancellation,
        // an empty source selection, or exhausting the source's budget is a terminal state as-is.
        if ("ok".equals(status) && !cancelledNow(cancelFlag)) {
            status = runResumeScanIfPossible(runId, sources, resumeId, cancelFlag, status);
        }

        sweepRunRepository.finish(runId, clock.instant(), status);
        SweepProgress finalProgress = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, String.join(",", sources), null));
        progressRegistry.publish(runId, new SweepProgress(runId, status, finalProgress.currentShard(),
                finalProgress.pagesFetched(), finalProgress.requestsMade(), finalProgress.cardsSeen(),
                finalProgress.jobsNew(), finalProgress.saturated(), finalProgress.companiesDone(),
                finalProgress.companiesTotal(), finalProgress.sources(), finalProgress.scan()));
        progressRegistry.finish(runId);
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
                new SweepProgress(runId, "scanning", null, 0, 0, 0, 0, false, 0, 0, String.join(",", sources), null));
        return new SweepProgress(runId, "scanning", current.currentShard(), current.pagesFetched(),
                current.requestsMade(), current.cardsSeen(), current.jobsNew(), current.saturated(),
                current.companiesDone(), current.companiesTotal(), current.sources(), current.scan());
    }

    private static boolean cancelledNow(AtomicBoolean cancelFlag) {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }
}
