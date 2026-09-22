package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ResumeMatchService;
import com.ubaid.jobdash.apply.CompanyLinkFinder;
import com.ubaid.jobdash.apply.CompanyLinkProperties;
import com.ubaid.jobdash.source.location.LocationClassifier;
import com.ubaid.jobdash.ai.ScanProgressListener;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link RunOrchestrator}'s dispatch logic with mocked collaborators - no real HTTP,
 * SQLite, or Claude CLI involved. The ATS path runs on a real virtual thread (same as
 * production), so assertions on its eventual effects use Mockito's {@code timeout()} verification
 * mode rather than sleeping.
 */
@ExtendWith(MockitoExtension.class)
class RunOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final long RUN_ID = 99L;

    @Mock
    private SweepService sweepService;
    @Mock
    private AtsSweepService atsSweepService;
    @Mock
    private DetailFetchService detailFetchService;
    @Mock
    private SweepRunRepository sweepRunRepository;
    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeMatchService resumeMatchService;
    @Mock
    private LocationClassifier locationClassifier;
    @Mock
    private CompanyLinkFinder companyLinkFinder;

    private RunOrchestrator orchestrator;
    private RunProgressRegistry registry;
    private Clock clock;

    private static final SweepRunRequest SWEEP_REQUEST =
            new SweepRunRequest("engineer", "remote", 24, false, false, null, null);

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        registry = new RunProgressRegistry();
        orchestrator = new RunOrchestrator(sweepService, atsSweepService, detailFetchService, sweepRunRepository,
                resumeRepository, resumeMatchService, locationClassifier, companyLinkFinder, linkProperties(true),
                registry, clock);
    }

    @Test
    void linkedInRunCollectsThenFetchesDetailsThenScansAndNeverTouchesAts() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", 3L)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        // An explicit resume id is used as-is; the default resume is never consulted.
        long runId = orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), 3L, false);

        assertThat(runId).isEqualTo(RUN_ID);
        InOrder order = inOrder(sweepService, detailFetchService, resumeMatchService, sweepRunRepository);
        order.verify(sweepService, timeout(2000)).collect(eq(RUN_ID), eq(SWEEP_REQUEST));
        order.verify(detailFetchService, timeout(2000)).fetchForRun(eq(RUN_ID), any());
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        order.verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(atsSweepService, locationClassifier);
        // The row is created by SweepService (sources 'linkedin'), never by the orchestrator itself.
        verify(sweepRunRepository, never()).create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    /** A collection that stopped early has no budget for details; the scan still looks at what exists. */
    @Test
    void linkedInRunThatWasCappedSkipsDetailsButStillScans() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", null)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("capped");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(3L)));

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("capped"));
        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verifyNoInteractions(detailFetchService);
    }

    // --- mixed runs -------------------------------------------------------------------------

    @Test
    void mixedRunCollectsLinkedInThenBoardsThenDetailsThenClassifiesThenScans() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin,greenhouse", 3L)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin", "greenhouse"), 3L, true);

        InOrder order = inOrder(sweepService, atsSweepService, detailFetchService, locationClassifier,
                resumeMatchService, sweepRunRepository);
        order.verify(sweepService, timeout(2000)).collect(eq(RUN_ID), eq(SWEEP_REQUEST));
        order.verify(atsSweepService, timeout(2000)).run(eq(RUN_ID),
                argThat(r -> r.atsNames().equals(List.of("greenhouse")) && r.usOnly()), any());
        order.verify(detailFetchService, timeout(2000)).fetchForRun(eq(RUN_ID), any());
        order.verify(locationClassifier, timeout(2000)).classifyRun(eq(RUN_ID), any());
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        order.verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        // One row for the whole run, created via SweepService with the full source list.
        verify(sweepRunRepository, never()).create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    /** LinkedIn's breaker opening must not stop the boards, and the boards' rows still get scanned. */
    @Test
    void mixedRunStillCollectsBoardsWhenLinkedInWasBlocked() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin,lever", null)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("blocked");
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(3L)));

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin", "lever"), null, false);

        verify(atsSweepService, timeout(2000)).run(eq(RUN_ID), any(), any());
        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("blocked"));
        verifyNoInteractions(detailFetchService);
    }

    /** No boards enabled is not a failure when LinkedIn was part of the run and collected fine. */
    @Test
    void mixedRunWithNoEnabledBoardsFinishesOk() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin,workday", null)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("no_sources");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin", "workday"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
    }

    @Test
    void terminalStatusIsTheFirstNonOkPhaseInPipelineOrder() {
        assertThat(RunOrchestrator.terminalStatus(false, "ok", "ok", "ok")).isEqualTo("ok");
        assertThat(RunOrchestrator.terminalStatus(false, null, "ok", null)).isEqualTo("ok");
        assertThat(RunOrchestrator.terminalStatus(false, "ok", null, "budget_exhausted")).isEqualTo("budget_exhausted");
        assertThat(RunOrchestrator.terminalStatus(false, "blocked", "ok", null)).isEqualTo("blocked");
        assertThat(RunOrchestrator.terminalStatus(false, "ok", "budget_exhausted", "capped")).isEqualTo("budget_exhausted");
        assertThat(RunOrchestrator.terminalStatus(true, "ok", "ok", "ok")).isEqualTo("cancelled");
    }

    /**
     * The detail phase stopping early (budget, breaker) must not throw away the descriptions it
     * DID fetch: the scan still runs, and the run reports the detail phase's stop reason.
     */
    @Test
    void linkedInRunStillScansWhenTheDetailPhaseRanOutOfBudget() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", null)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("budget_exhausted");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(3L)));

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), null, false);

        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("budget_exhausted"));
    }

    /** A detail-phase bug must never leave a successfully collected run unfinished. */
    @Test
    void aThrowingDetailPhaseStillFinishesTheLinkedInRunOk() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", null)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenThrow(new RuntimeException("boom"));
        when(resumeRepository.findDefault()).thenReturn(Optional.empty());

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
    }

    @Test
    void atsRunInvokesTheMatchServiceAfterASuccessfulCollection() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(3L)));

        long runId = orchestrator.startRun(SWEEP_REQUEST, List.of("greenhouse"), null, false);

        assertThat(runId).isEqualTo(RUN_ID);
        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(sweepService);
    }

    @Test
    void aRunWithNoResumeAtAllStillFinishesOk() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.empty());

        orchestrator.startRun(SWEEP_REQUEST, List.of("lever"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verify(resumeMatchService, never()).scan(anyLong(), anyLong(), any());
    }

    @Test
    void aThrowingMatchServiceStillLeavesTheRunOk() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(9L)));
        when(resumeMatchService.scan(eq(RUN_ID), eq(9L), any(), any())).thenThrow(new RuntimeException("boom"));

        orchestrator.startRun(SWEEP_REQUEST, List.of("workday"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
    }

    private static Resume resume(long id) {
        return new Resume(id, "My Resume", "resume.pdf", "application/pdf", "resumes/1.pdf",
                "resume text", 11, true, NOW);
    }

    @Test
    void anAtsRunClassifiesLocationsBeforeScanning() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.of(resume(3L)));

        orchestrator.startRun(SWEEP_REQUEST, List.of("greenhouse"), null, true);

        InOrder order = inOrder(atsSweepService, locationClassifier, resumeMatchService);
        order.verify(atsSweepService, timeout(2000)).run(eq(RUN_ID), any(), any());
        order.verify(locationClassifier, timeout(2000)).classifyRun(eq(RUN_ID), any());
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
    }

    /** An explicitly worldwide run must not pay for a classification whose result it would ignore. */
    @Test
    void aRunWithUsOnlyOffSkipsClassificationEntirely() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.empty());

        orchestrator.startRun(SWEEP_REQUEST, List.of("greenhouse"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(locationClassifier);
    }

    /** A classifier blowing up must not turn a successful collection into a failed run. */
    @Test
    void aThrowingClassifierStillLeavesTheRunOk() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.empty());
        when(locationClassifier.classifyRun(eq(RUN_ID), any())).thenThrow(new RuntimeException("boom"));

        orchestrator.startRun(SWEEP_REQUEST, List.of("greenhouse"), null, true);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
    }

    // ---- resume: the Retry behind a run LinkedIn's cooldown cut short --------------------

    private static SweepRun finishedRun(String status, String sources, Long resumeId) {
        return new SweepRun(RUN_ID, NOW.minusSeconds(600), NOW.minusSeconds(60), status, "engineer", "remote",
                24, false, null, null, 19, 167, 308, 254, false, sources, resumeId, 0, 0, 0, 0);
    }

    /** Resuming a blocked LinkedIn run reads the descriptions it left unread, then scans - never re-collects. */
    @Test
    void resumingABlockedLinkedInRunFetchesDetailsThenScansWithoutCollectingAgain() {
        when(sweepRunRepository.findById(RUN_ID))
                .thenReturn(Optional.of(finishedRun("blocked", "linkedin,greenhouse", 3L)));
        when(sweepRunRepository.reopen(RUN_ID, "fetching_details")).thenReturn(1);
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        orchestrator.resumeRun(RUN_ID);

        InOrder order = inOrder(sweepRunRepository, detailFetchService, resumeMatchService);
        order.verify(sweepRunRepository).reopen(RUN_ID, "fetching_details");
        order.verify(detailFetchService, timeout(2000)).fetchForRun(eq(RUN_ID), any());
        // The run's own resume is used, not the default one.
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        order.verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(sweepService, atsSweepService, locationClassifier);
        verify(resumeRepository, never()).findDefault();
    }

    /** If the breaker is still open when the resume runs, the run ends "blocked" again - and stays retryable. */
    @Test
    void aResumeThatIsBlockedAgainFinishesBlocked() {
        when(sweepRunRepository.findById(RUN_ID)).thenReturn(Optional.of(finishedRun("blocked", "linkedin", 3L)));
        when(sweepRunRepository.reopen(RUN_ID, "fetching_details")).thenReturn(1);
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("blocked");

        orchestrator.resumeRun(RUN_ID);

        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("blocked"));
    }

    /** A boards-only run has no LinkedIn descriptions to fetch; resuming it is just a re-scan. */
    @Test
    void resumingAnAtsOnlyRunSkipsTheDetailPhase() {
        when(sweepRunRepository.findById(RUN_ID)).thenReturn(Optional.of(finishedRun("ok", "greenhouse,lever", null)));
        when(sweepRunRepository.reopen(RUN_ID, "scanning")).thenReturn(1);
        when(resumeRepository.findDefault()).thenReturn(Optional.of(
                new Resume(7L, "cv", "cv.pdf", "application/pdf", "data/resumes/cv.pdf", "text", 4, true, NOW)));

        orchestrator.resumeRun(RUN_ID);

        verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(7L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(detailFetchService, sweepService, atsSweepService);
    }

    /** reopen() affecting no row means the run is still in flight: refuse rather than start a second thread on it. */
    @Test
    void resumingARunStillInFlightIsRefused() {
        when(sweepRunRepository.findById(RUN_ID)).thenReturn(Optional.of(finishedRun("running", "linkedin", 3L)));
        when(sweepRunRepository.reopen(RUN_ID, "fetching_details")).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.resumeRun(RUN_ID))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(detailFetchService, resumeMatchService);
        verify(sweepRunRepository, never()).finish(anyLong(), any(), any());
    }

    // ---- Company links phase (status "finding_links") ---------------------------------------------------------

    private static CompanyLinkProperties linkProperties(boolean enabled) {
        return new CompanyLinkProperties(enabled, "sonnet", 4, 3, 70, 80, 3.0, java.time.Duration.ofMinutes(15));
    }

    /** The finder runs after the scan, over the scan's resume, with "finding_links" published meanwhile. */
    @Test
    void executeRunFindsCompanyLinksAfterTheScanWithTheScannedResume() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", 3L)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");
        java.util.concurrent.atomic.AtomicReference<String> statusDuringSearch = new java.util.concurrent.atomic.AtomicReference<>();
        when(companyLinkFinder.find(eq(RUN_ID), eq(3L), any())).thenAnswer(inv -> {
            statusDuringSearch.set(registry.progress(RUN_ID).map(SweepProgress::status).orElse(null));
            return Optional.empty();
        });

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), 3L, false);

        InOrder order = inOrder(resumeMatchService, companyLinkFinder, sweepRunRepository);
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        order.verify(companyLinkFinder, timeout(2000)).find(eq(RUN_ID), eq(3L), any());
        order.verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        assertThat(statusDuringSearch.get()).isEqualTo("finding_links");
    }

    /** The Retry/resume path searches after its own scan too, with the run's own resume. */
    @Test
    void executeResumeFindsCompanyLinksAfterTheScan() {
        when(sweepRunRepository.findById(RUN_ID))
                .thenReturn(Optional.of(finishedRun("blocked", "linkedin,greenhouse", 3L)));
        when(sweepRunRepository.reopen(RUN_ID, "fetching_details")).thenReturn(1);
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        orchestrator.resumeRun(RUN_ID);

        InOrder order = inOrder(resumeMatchService, companyLinkFinder, sweepRunRepository);
        order.verify(resumeMatchService, timeout(2000))
                .scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));
        order.verify(companyLinkFinder, timeout(2000)).find(eq(RUN_ID), eq(3L), any());
        order.verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
    }

    /** A cancellation during the scan must stop the search from ever starting. */
    @Test
    void companyLinksAreSkippedWhenCancelledDuringTheScan() {
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", 3L)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");
        org.mockito.Mockito.doAnswer(inv -> {
            registry.cancelFlag(RUN_ID).set(true);
            return null;
        }).when(resumeMatchService).scan(eq(RUN_ID), eq(3L), any(BooleanSupplier.class), any(ScanProgressListener.class));

        orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), 3L, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("cancelled"));
        verifyNoInteractions(companyLinkFinder);
    }

    /** apply.company-links.enabled=false skips the search entirely. */
    @Test
    void companyLinksAreSkippedWhenDisabled() {
        RunOrchestrator disabledOrchestrator = new RunOrchestrator(sweepService, atsSweepService, detailFetchService,
                sweepRunRepository, resumeRepository, resumeMatchService, locationClassifier, companyLinkFinder,
                linkProperties(false), registry, clock);
        when(sweepService.createRun(SWEEP_REQUEST, "linkedin", 3L)).thenReturn(RUN_ID);
        when(sweepService.collect(eq(RUN_ID), eq(SWEEP_REQUEST))).thenReturn("ok");
        when(detailFetchService.fetchForRun(eq(RUN_ID), any())).thenReturn("ok");

        disabledOrchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), 3L, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(companyLinkFinder);
    }

    /** No resume means no scan, so no recommendations to search for. */
    @Test
    void companyLinksAreSkippedWhenThereIsNoResumeToScanWith() {
        lenient().when(sweepRunRepository.create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(RUN_ID);
        when(atsSweepService.run(eq(RUN_ID), any(), any())).thenReturn("ok");
        when(resumeRepository.findDefault()).thenReturn(Optional.empty());

        orchestrator.startRun(SWEEP_REQUEST, List.of("greenhouse"), null, false);

        verify(sweepRunRepository, timeout(2000)).finish(eq(RUN_ID), any(), eq("ok"));
        verifyNoInteractions(companyLinkFinder);
    }
}
