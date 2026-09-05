package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run left open by a killed process used to wedge the application permanently: a new run was
 * refused as "already in progress", and cancelling it was refused too because it was not in the
 * running process's registry. These tests pin the way out.
 */
class OrphanedRunReaperTest extends AbstractStoreTest {

    private static final Instant BOOT = Instant.parse("2026-09-08T12:00:00Z");

    private OrphanedRunReaper reaper() {
        return new OrphanedRunReaper(sweepRunRepository, Clock.fixed(BOOT, ZoneOffset.UTC));
    }

    @Test
    void closesOutARunLeftOpenByAKilledProcess() {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-08T11:00:00Z"), "java", "remote",
                24, false, null, "greenhouse", null);
        assertThat(sweepRunRepository.findRunning()).as("precondition: the run looks in-flight").isPresent();

        reaper().run(null);

        SweepRun run = sweepRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo("interrupted");
        assertThat(run.finishedAt()).isEqualTo(BOOT);
        assertThat(sweepRunRepository.findRunning())
                .as("with nothing in flight, POST /api/runs can start a new run again")
                .isEmpty();
    }

    @Test
    void leavesAlreadyFinishedRunsAlone() {
        long done = sweepRunRepository.create(Instant.parse("2026-09-08T09:00:00Z"), "java", "remote",
                24, false, null, "greenhouse", null);
        sweepRunRepository.finish(done, Instant.parse("2026-09-08T09:30:00Z"), "ok");

        reaper().run(null);

        SweepRun run = sweepRunRepository.findById(done).orElseThrow();
        assertThat(run.status()).as("a completed run must keep its real terminal status").isEqualTo("ok");
        assertThat(run.finishedAt()).isEqualTo(Instant.parse("2026-09-08T09:30:00Z"));
    }

    @Test
    void isANoOpWhenThereIsNothingToReap() {
        reaper().run(null);
        assertThat(sweepRunRepository.findRecent(10)).isEmpty();
    }
}
