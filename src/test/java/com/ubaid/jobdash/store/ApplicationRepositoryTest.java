package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.JobApplication;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ApplicationRepository} against a real (temp-file) database. */
class ApplicationRepositoryTest extends AbstractStoreTest {

    private long insertJob(long runId, long sourceJobId) {
        JobCardInsert card = new JobCardInsert("lever", String.valueOf(sourceJobId), "Backend Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://acme.com/jobs/" + sourceJobId, "https://acme.com", "Description");
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
    }

    private long newRun() {
        return sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
    }

    @Test
    void createUpdateAndFindByBatch() {
        long runId = newRun();
        long jobId = insertJob(runId, 1L);
        long batchId = applyBatchRepository.create(1L, false, 1, Instant.parse("2026-09-10T00:00:00Z"));

        long appId = applicationRepository.create(batchId, jobId, "queued");
        List<JobApplication> queued = applicationRepository.findByBatch(batchId);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).status()).isEqualTo("queued");
        assertThat(queued.get(0).startedAt()).isNull();

        Instant startedAt = Instant.parse("2026-09-10T00:01:00Z");
        Instant finishedAt = Instant.parse("2026-09-10T00:02:00Z");
        applicationRepository.update(appId, "submitted", "Application submitted", 0.35, startedAt, finishedAt);

        JobApplication updated = applicationRepository.findByBatch(batchId).get(0);
        assertThat(updated.status()).isEqualTo("submitted");
        assertThat(updated.notes()).isEqualTo("Application submitted");
        assertThat(updated.costUsd()).isEqualTo(0.35);
        assertThat(updated.startedAt()).isEqualTo(startedAt);
        assertThat(updated.finishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void findLatestByJobIdsReturnsTheNewestRowPerJob() {
        long runId = newRun();
        long jobId = insertJob(runId, 2L);
        long otherJobId = insertJob(runId, 3L);

        long batch1 = applyBatchRepository.create(1L, false, 1, Instant.parse("2026-09-01T00:00:00Z"));
        long firstAttempt = applicationRepository.create(batch1, jobId, "queued");
        applicationRepository.update(firstAttempt, "failed", "first attempt failed", 0.10,
                Instant.parse("2026-09-01T00:01:00Z"), Instant.parse("2026-09-01T00:02:00Z"));

        long batch2 = applyBatchRepository.create(1L, false, 1, Instant.parse("2026-09-02T00:00:00Z"));
        long secondAttempt = applicationRepository.create(batch2, jobId, "queued");
        applicationRepository.update(secondAttempt, "submitted", "second attempt submitted", 0.20,
                Instant.parse("2026-09-02T00:01:00Z"), Instant.parse("2026-09-02T00:02:00Z"));

        long otherAttempt = applicationRepository.create(batch1, otherJobId, "queued");
        applicationRepository.update(otherAttempt, "needs_review", "other job", 0.05,
                Instant.parse("2026-09-01T00:01:00Z"), Instant.parse("2026-09-01T00:02:00Z"));

        Map<Long, JobApplication> latest = applicationRepository.findLatestByJobIds(List.of(jobId, otherJobId));

        assertThat(latest).hasSize(2);
        assertThat(latest.get(jobId).status()).isEqualTo("submitted");
        assertThat(latest.get(jobId).notes()).isEqualTo("second attempt submitted");
        assertThat(latest.get(otherJobId).status()).isEqualTo("needs_review");
    }
}
