package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.Resume;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiMatchRepositoryTest extends AbstractStoreTest {

    private AiMatchRepository aiMatchRepository;
    private ResumeRepository resumeRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUpAdditional() {
        aiMatchRepository = new AiMatchRepository(client);
        resumeRepository = new ResumeRepository(client);
    }

    private long insertResume(String name) {
        return resumeRepository.insert(new Resume(0, name, "resume.pdf", "application/pdf", "resumes/1.pdf",
                "some resume text", 17, false, Instant.now()));
    }

    private long insertJob(long sourceJobId, String description) {
        JobCardInsert card = new JobCardInsert("lever", String.valueOf(sourceJobId), "Backend Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://acme.com/jobs/" + sourceJobId, "https://acme.com", description);
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        long jobId = client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
        jobListingRepository.applyVerdicts(
                List.of(new JobListingRepository.VerdictUpdate(jobId, FilterVerdict.PASS, null)), 1);
        return jobId;
    }

    @Test
    void upsertAllInsertsThenConflictUpdatesTheSamePair() {
        long resumeId = insertResume("r1");
        long jobId = insertJob(1, "desc");

        aiMatchRepository.upsertAll(List.of(
                new AiMatch(jobId, resumeId, true, "good fit", "sonnet", 1L, Instant.parse("2026-09-01T00:00:00Z"))));

        Map<Long, AiMatch> first = aiMatchRepository.findByJobIds(List.of(jobId), resumeId);
        assertThat(first.get(jobId).recommended()).isTrue();
        assertThat(first.get(jobId).reason()).isEqualTo("good fit");

        // Re-scanning the same pair must update the existing row, not create a second one.
        aiMatchRepository.upsertAll(List.of(
                new AiMatch(jobId, resumeId, false, "no longer a fit", "sonnet", 2L, Instant.parse("2026-09-02T00:00:00Z"))));

        Map<Long, AiMatch> second = aiMatchRepository.findByJobIds(List.of(jobId), resumeId);
        assertThat(second).hasSize(1);
        assertThat(second.get(jobId).recommended()).isFalse();
        assertThat(second.get(jobId).reason()).isEqualTo("no longer a fit");

        long rowCount = client.sql("select count(*) from ai_match").query(Long.class).single();
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void findScannedJobIdsReturnsOnlyThoseCachedForThatResume() {
        long resumeId1 = insertResume("r1");
        long resumeId2 = insertResume("r2");
        long job1 = insertJob(10, "desc");
        long job2 = insertJob(11, "desc");

        aiMatchRepository.upsertAll(List.of(
                new AiMatch(job1, resumeId1, true, "fit", "sonnet", null, Instant.now())));

        Set<Long> scannedForResume1 = aiMatchRepository.findScannedJobIds(List.of(job1, job2), resumeId1);
        assertThat(scannedForResume1).containsExactly(job1);

        Set<Long> scannedForResume2 = aiMatchRepository.findScannedJobIds(List.of(job1, job2), resumeId2);
        assertThat(scannedForResume2).isEmpty();
    }

    @Test
    void deleteByResumeClearsOnlyThatResumesVerdicts() {
        long resumeId1 = insertResume("r1");
        long resumeId2 = insertResume("r2");
        long job1 = insertJob(20, "desc");

        aiMatchRepository.upsertAll(List.of(
                new AiMatch(job1, resumeId1, true, "fit", "sonnet", null, Instant.now()),
                new AiMatch(job1, resumeId2, false, "not a fit", "sonnet", null, Instant.now())));

        aiMatchRepository.deleteByResume(resumeId1);

        assertThat(aiMatchRepository.findByJobIds(List.of(job1), resumeId1)).isEmpty();
        assertThat(aiMatchRepository.findByJobIds(List.of(job1), resumeId2)).hasSize(1);
    }

    @Test
    void countByRunAndResumeTalliesRecommendedAndNotRecommended() {
        long resumeId = insertResume("r1");
        long job1 = insertJob(30, "desc");
        long job2 = insertJob(31, "desc");
        long job3 = insertJob(32, "desc");

        aiMatchRepository.upsertAll(List.of(
                new AiMatch(job1, resumeId, true, "fit", "sonnet", 99L, Instant.now()),
                new AiMatch(job2, resumeId, true, "fit", "sonnet", 99L, Instant.now()),
                new AiMatch(job3, resumeId, false, "not a fit", "sonnet", 99L, Instant.now())));

        AiMatchRepository.Counts counts = aiMatchRepository.countByRunAndResume(99L, resumeId);

        assertThat(counts.recommended()).isEqualTo(2);
        assertThat(counts.notRecommended()).isEqualTo(1);
    }

    @Test
    void findByJobIdsWithEmptyCollectionReturnsEmptyMapWithoutQuerying() {
        long resumeId = insertResume("r1");
        assertThat(aiMatchRepository.findByJobIds(List.of(), resumeId)).isEmpty();
    }
}
