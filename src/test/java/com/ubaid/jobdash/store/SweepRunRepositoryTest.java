package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.SweepRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SweepRunRepository}, focused on reading a run back out.
 *
 * <p>The nullable numeric columns are the interesting part. SQLite's JDBC driver returns the
 * narrowest type a value fits in, so a nullable column read through {@code (Long) getObject(...)}
 * works for NULL and throws {@link ClassCastException} for a real value — a failure that only
 * appears once the feature is actually used.
 */
class SweepRunRepositoryTest extends AbstractStoreTest {

    @Test
    void readsBackARunThatHasAResumeAttached() {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-08T10:00:00Z"), "java", "remote",
                24, false, null, "greenhouse,lever", 7L);

        SweepRun run = sweepRunRepository.findById(runId).orElseThrow();

        assertThat(run.resumeId())
                .as("a non-null resume_id must survive the round trip; SQLite hands it back as an "
                        + "Integer, so casting it straight to Long throws at runtime")
                .isEqualTo(7L);
        assertThat(run.sources()).isEqualTo("greenhouse,lever");
    }

    @Test
    void readsBackARunWithNoResumeAsNull() {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-08T10:00:00Z"), "java", "remote",
                24, false, null, "linkedin", null);

        SweepRun run = sweepRunRepository.findById(runId).orElseThrow();

        assertThat(run.resumeId()).isNull();
        assertThat(run.sources()).isEqualTo("linkedin");
    }

    /**
     * {@code GET /api/runs} is the first call the dashboard makes on load, and it goes through
     * {@code findRecent}. A mapping failure here takes the whole UI down, not just one endpoint.
     */
    @Test
    void findRecentAndFindRunningMapRunsThatHaveAResumeAttached() {
        sweepRunRepository.create(Instant.parse("2026-09-08T10:00:00Z"), "java", "remote",
                24, false, null, "greenhouse", 3L);

        List<SweepRun> recent = sweepRunRepository.findRecent(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).resumeId()).isEqualTo(3L);

        assertThat(sweepRunRepository.findRunning()).isPresent();
        assertThat(sweepRunRepository.findRunning().orElseThrow().resumeId()).isEqualTo(3L);
    }

    @Test
    void aRunCreatedThroughTheLegacyOverloadDefaultsToLinkedInWithNoResume() {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-08T10:00:00Z"), "java", "remote",
                24, false, null);

        SweepRun run = sweepRunRepository.findById(runId).orElseThrow();

        assertThat(run.sources()).isEqualTo("linkedin");
        assertThat(run.resumeId()).isNull();
    }
}
