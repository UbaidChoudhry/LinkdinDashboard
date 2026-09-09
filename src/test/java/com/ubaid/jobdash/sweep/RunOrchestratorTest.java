package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ResumeMatchService;
import com.ubaid.jobdash.source.location.LocationClassifier;
import com.ubaid.jobdash.ai.ScanProgressListener;
import com.ubaid.jobdash.domain.Resume;
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

    private RunOrchestrator orchestrator;

    private static final SweepRunRequest SWEEP_REQUEST =
            new SweepRunRequest("engineer", "remote", 24, false, false, null, null);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RunProgressRegistry registry = new RunProgressRegistry();
        orchestrator = new RunOrchestrator(sweepService, atsSweepService, detailFetchService, sweepRunRepository,
                resumeRepository, resumeMatchService, locationClassifier, registry, clock);
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
}
