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
        orchestrator = new RunOrchestrator(sweepService, atsSweepService, sweepRunRepository,
                resumeRepository, resumeMatchService, locationClassifier, registry, clock);
    }

    @Test
    void linkedInOnlyDelegatesToSweepServiceAndNeverInvokesTheMatchService() {
        when(sweepService.startRun(SWEEP_REQUEST)).thenReturn(RUN_ID);

        long runId = orchestrator.startRun(SWEEP_REQUEST, List.of("linkedin"), null, false);

        assertThat(runId).isEqualTo(RUN_ID);
        verify(sweepService).startRun(SWEEP_REQUEST);
        verifyNoInteractions(atsSweepService, resumeMatchService);
        verify(sweepRunRepository, never()).create(any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
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
