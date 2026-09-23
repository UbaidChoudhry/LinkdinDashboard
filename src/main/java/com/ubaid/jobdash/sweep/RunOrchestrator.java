package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ResumeMatchService;
import com.ubaid.jobdash.apply.CompanyLinkFinder;
import com.ubaid.jobdash.apply.CompanyLinkProperties;
import com.ubaid.jobdash.source.location.LocationClassifier;
import com.ubaid.jobdash.source.location.RemoteClassifier;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.SweepRun;
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
 *   4b. Remote or not        RemoteClassifier              (unless cancelled; every undecided row, any run)
 *   5. AI resume scan        ResumeMatchService.scan       (unless cancelled)
 *   6. Company links         CompanyLinkFinder.find        (if 5 ran and apply.company-links is enabled)
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
    private final RemoteClassifier remoteClassifier;
    private final CompanyLinkFinder companyLinkFinder;
    private final CompanyLinkProperties companyLinkProperties;
    private final RunProgressRegistry progressRegistry;
    private final Clock clock;

    public RunOrchestrator(SweepService sweepService, AtsSweepService atsSweepService,
                            DetailFetchService detailFetchService,
                            SweepRunRepository sweepRunRepository, ResumeRepository resumeRepository,
                            ResumeMatchService resumeMatchService, LocationClassifier locationClassifier,
                            RemoteClassifier remoteClassifier, CompanyLinkFinder companyLinkFinder, CompanyLinkProperties companyLinkProperties,
                            RunProgressRegistry progressRegistry, Clock clock) {
        this.sweepService = sweepService;
        this.atsSweepService = atsSweepService;
        this.detailFetchService = detailFetchService;
        this.sweepRunRepository = sweepRunRepository;
        this.resumeRepository = resumeRepository;
        this.resumeMatchService = resumeMatchService;
        this.locationClassifier = locationClassifier;
        this.remoteClassifier = remoteClassifier;
        this.companyLinkFinder = companyLinkFinder;
        this.companyLinkProperties = companyLinkProperties;
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

    /**
     * Re-opens a finished run for the phases a LinkedIn cooldown (or a cap / the daily budget)
     * cut short: the description fetch for rows the run collected but never read, then the AI
     * scan over everything that now carries a description. It does <b>not</b> re-run the
     * search itself - the pages the run never reached are a new run's job. Nothing is
     * re-collected, so the boards are not touched and no location classification is repeated.
     * <p>
     * Returns immediately; the work happens on a virtual thread exactly like {@link #startRun},
     * so the same SSE stream and cancel path apply. The run's terminal status becomes the
     * detail phase's outcome ("ok", or "blocked" again if the breaker re-opened).
     *
     * @throws IllegalArgumentException if no such run exists
     * @throws IllegalStateException    if the run is still in flight (the caller's guard should
     *                                  already have refused it)
     */
    public void resumeRun(long runId) {
        SweepRun run = sweepRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("No run found with id " + runId));
        List<String> sources = run.sources() == null || run.sources().isBlank()
                ? List.of("linkedin")
                : List.of(run.sources().split(","));
        boolean withLinkedIn = sources.contains("linkedin");
        String initialStatus = withLinkedIn ? "fetching_details" : "scanning";
        if (sweepRunRepository.reopen(runId, initialStatus) == 0) {
            throw new IllegalStateException("Run " + runId + " is still in flight and cannot be resumed.");
        }
        // Carry the row's counters into the live snapshot so the panel keeps showing what the
        // run already collected while its remaining phases work.
        progressRegistry.start(runId, new SweepProgress(runId, initialStatus, null, run.pagesFetched(),
                run.requestsMade(), run.cardsSeen(), run.jobsNew(), run.saturated(), run.companiesDone(),
                run.companiesTotal(), run.detailsDone(), run.detailsTotal(), String.join(",", sources), null));

        Thread thread = Thread.ofVirtual().name("run-" + runId + "-resume")
                .unstarted(() -> executeResume(runId, sources, run.resumeId(), withLinkedIn));
        progressRegistry.registerThread(runId, thread);
        thread.start();
    }

    private void executeResume(long runId, List<String> sources, Long resumeId, boolean withLinkedIn) {
        progressRegistry.registerCurrentThreadIfAbsent(runId);
        AtomicBoolean cancelFlag = progressRegistry.cancelFlag(runId);
        String detailStatus = null;
        String status = "failed";
        try {
            if (withLinkedIn && !cancelledNow(cancelFlag)) {
                detailStatus = fetchDetailsSafely(runId, cancelFlag);
            }
            if (!cancelledNow(cancelFlag)) {
                classifyRemoteSafely(runId, cancelFlag);
            }
            Long effectiveResumeId = null;
            if (!cancelledNow(cancelFlag)) {
                effectiveResumeId = runResumeScanIfPossible(runId, sources, resumeId, cancelFlag);
            }
            // Company links need the scan's verdicts (only recommended jobs are searched), so they
            // follow it, over the same resume - and are skipped with it when there is no resume.
            if (!cancelledNow(cancelFlag)) {
                findCompanyLinksSafely(runId, effectiveResumeId, cancelFlag);
            }
            status = terminalStatus(cancelledNow(cancelFlag), null, null, detailStatus);
        } finally {
            finishRun(runId, status, sources);
        }
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
            // After the detail fetch, whose descriptions are what this reads, and before the scan.
            // Needs no resume, so it runs even when the scan will be skipped.
            if (!cancelledNow(cancelFlag)) {
                classifyRemoteSafely(runId, cancelFlag);
            }
            // Scan whatever now carries a description, even if a phase stopped early: a budget
            // refusal partway through the detail phase still leaves real descriptions to read,
            // and a blocked LinkedIn phase says nothing about the boards. Only a cancellation
            // skips it.
            Long effectiveResumeId = null;
            if (!cancelledNow(cancelFlag)) {
                effectiveResumeId = runResumeScanIfPossible(runId, sources, resumeId, cancelFlag);
            }
            // Company links need the scan's verdicts (only recommended jobs are searched), so they
            // follow it, over the same resume - and are skipped with it when there is no resume.
            if (!cancelledNow(cancelFlag)) {
                findCompanyLinksSafely(runId, effectiveResumeId, cancelFlag);
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
     * Decides remote-or-not for every row still undecided, from this run or any earlier one - the
     * only way a LinkedIn row ever gets an answer (HANDOFF.md §15). {@link RemoteClassifier} never
     * throws; the wrapper is the same belt-and-braces as {@link #classifyLocations}.
     */
    private void classifyRemoteSafely(long runId, AtomicBoolean cancelFlag) {
        try {
            remoteClassifier.classifyPending(() -> cancelledNow(cancelFlag));
        } catch (Exception e) {
            log.warn("remote classification for run {} threw unexpectedly "
                    + "(RemoteClassifier should never throw): {}", runId, e.toString());
        }
    }

    /**
     * Resolves the resume to scan against (explicit id, else the default, else "no resume at
     * all") and runs the AI scan. A missing resume skips the scan silently - it must never fail a
     * run that successfully collected jobs. {@link ResumeMatchService} already guarantees it
     * never throws, but this still wraps the call: an AI failure must never turn a successful
     * collection run into a failed one.
     *
     * @return the resume id the scan ran against, or null if there was none (phase 6, which works
     * from that resume's recommendations, is then skipped too)
     */
    private Long runResumeScanIfPossible(long runId, List<String> sources, Long resumeId,
                                          AtomicBoolean cancelFlag) {
        Long effectiveResumeId = resumeId != null ? resumeId
                : resumeRepository.findDefault().map(Resume::id).orElse(null);
        if (effectiveResumeId == null) {
            return null;
        }

        progressRegistry.publish(runId, phaseProgress(runId, sources, "scanning"));
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
        return effectiveResumeId;
    }

    /**
     * Phase 6: looks for each newly recommended LinkedIn job on the employer's own careers site
     * ({@link CompanyLinkFinder}), publishing the {@code "finding_links"} in-flight status meanwhile.
     * Skipped when disabled or when there was no resume to scan with. The finder never throws;
     * this still wraps the call, the same idiom as {@link #fetchDetailsSafely}: a link search must
     * never turn a successful run into a failed one.
     */
    private void findCompanyLinksSafely(long runId, Long effectiveResumeId, AtomicBoolean cancelFlag) {
        if (effectiveResumeId == null || !companyLinkProperties.enabled()) {
            return;
        }
        progressRegistry.progress(runId).ifPresent(current ->
                progressRegistry.publish(runId, current.withStatus("finding_links")));
        try {
            companyLinkFinder.find(runId, effectiveResumeId, () -> cancelledNow(cancelFlag));
        } catch (Exception e) {
            log.warn("company link search for run {} threw unexpectedly (CompanyLinkFinder should never throw): {}",
                    runId, e.toString());
        }
    }

    private SweepProgress phaseProgress(long runId, List<String> sources, String status) {
        SweepProgress current = progressRegistry.progress(runId).orElse(
                new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, 0, 0, String.join(",", sources), null));
        return current.withStatus(status);
    }

    private static boolean cancelledNow(AtomicBoolean cancelFlag) {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }
}
