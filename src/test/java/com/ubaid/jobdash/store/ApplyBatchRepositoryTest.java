package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.ApplyBatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ApplyBatchRepository} against a real (temp-file) database. */
class ApplyBatchRepositoryTest extends AbstractStoreTest {

    @Test
    void createUpdateProgressFinishAndFindInFlightLifecycle() {
        Instant startedAt = Instant.parse("2026-09-10T00:00:00Z");
        long batchId = applyBatchRepository.create(1L, false, 3, startedAt);

        Optional<ApplyBatch> created = applyBatchRepository.findById(batchId);
        assertThat(created).isPresent();
        assertThat(created.get().status()).isEqualTo("running");
        assertThat(created.get().total()).isEqualTo(3);
        assertThat(created.get().submit()).isFalse();
        assertThat(created.get().finishedAt()).isNull();

        Optional<ApplyBatch> inFlight = applyBatchRepository.findInFlight();
        assertThat(inFlight).isPresent();
        assertThat(inFlight.get().id()).isEqualTo(batchId);

        applyBatchRepository.updateProgress(batchId, 2, 1, 1, 0, 0, 0.42);
        ApplyBatch progressed = applyBatchRepository.findById(batchId).orElseThrow();
        assertThat(progressed.done()).isEqualTo(2);
        assertThat(progressed.submitted()).isEqualTo(1);
        assertThat(progressed.needsReview()).isEqualTo(1);
        assertThat(progressed.costUsd()).isEqualTo(0.42);

        Instant finishedAt = Instant.parse("2026-09-10T00:10:00Z");
        applyBatchRepository.finish(batchId, "ok", finishedAt);

        ApplyBatch finished = applyBatchRepository.findById(batchId).orElseThrow();
        assertThat(finished.status()).isEqualTo("ok");
        assertThat(finished.finishedAt()).isEqualTo(finishedAt);

        assertThat(applyBatchRepository.findInFlight()).isEmpty();

        List<ApplyBatch> recent = applyBatchRepository.findRecent(10);
        assertThat(recent).extracting(ApplyBatch::id).contains(batchId);
    }
}
