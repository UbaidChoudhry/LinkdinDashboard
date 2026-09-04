package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobListingRepositoryTest extends AbstractStoreTest {

    private static JobCardInsert card(long jobId, String title) {
        return new JobCardInsert("linkedin", String.valueOf(jobId), title, "Acme Corp", "Remote",
                Instant.parse("2026-08-27T00:00:00Z"),
                "https://linkedin.com/jobs/view/" + jobId, "https://linkedin.com/company/acme", null);
    }

    private static JobCardInsert card(String source, long sourceJobId, String title) {
        return new JobCardInsert(source, String.valueOf(sourceJobId), title, "Acme Corp", "Remote",
                Instant.parse("2026-08-27T00:00:00Z"),
                "https://" + source + "/jobs/view/" + sourceJobId, "https://" + source + "/company/acme", null);
    }

    /** Resolves a card's autoincrement surrogate {@code job_id} by its {@code source_job_id}. */
    private long jobIdFor(String sourceJobId) {
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", sourceJobId)
                .query(Long.class)
                .single();
    }

    @Test
    void upsertAllReturnsCountOfGenuinelyNewRows() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);

        int firstBatch = jobListingRepository.upsertAll(
                List.of(card(1, "Java Engineer"), card(2, "Backend Engineer")), runId, Instant.now());
        assertThat(firstBatch).isEqualTo(2);

        long runId2 = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        int secondBatch = jobListingRepository.upsertAll(
                List.of(card(2, "Backend Engineer"), card(3, "Platform Engineer")), runId2, Instant.now());
        assertThat(secondBatch).isEqualTo(1);
    }

    @Test
    void sameSourceJobIdUnderDifferentSourcesCreatesSeparateRows() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);

        int firstBatch = jobListingRepository.upsertAll(
                List.of(card("linkedin", 555, "Java Engineer")), runId, Instant.now());
        assertThat(firstBatch).isEqualTo(1);

        // Re-upserting the same (source, sourceJobId) must not create a second row.
        int repeatBatch = jobListingRepository.upsertAll(
                List.of(card("linkedin", 555, "Java Engineer")), runId, Instant.now());
        assertThat(repeatBatch).isEqualTo(0);

        // The same sourceJobId "555" under a different source is a genuinely different job.
        int differentSourceBatch = jobListingRepository.upsertAll(
                List.of(card("lever", 555, "Java Engineer")), runId, Instant.now());
        assertThat(differentSourceBatch).isEqualTo(1);

        Long rowCount = client.sql("select count(*) from job_listing where source_job_id = '555'")
                .query(Long.class).single();
        assertThat(rowCount).isEqualTo(2);
    }

    @Test
    void nullIncomingDescriptionDoesNotBlankAnExistingOne_nonNullReplacesIt() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        JobCardInsert withDescription = new JobCardInsert("lever", "abc-123", "Java Engineer", "Acme Corp",
                "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://lever.co/jobs/view/abc-123", "https://lever.co/company/acme", "Original description");
        jobListingRepository.upsertAll(List.of(withDescription), runId, Instant.now());

        long jobId = jobIdFor("abc-123");
        assertThat(jobListingRepository.findById(jobId).orElseThrow().description())
                .isEqualTo("Original description");

        // A re-fetch with a null description (e.g. a source that doesn't carry one) must not
        // blank out the description we already stored.
        JobCardInsert refetchWithNullDescription = new JobCardInsert("lever", "abc-123", "Java Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://lever.co/jobs/view/abc-123", "https://lever.co/company/acme", null);
        jobListingRepository.upsertAll(List.of(refetchWithNullDescription), runId, Instant.now());
        assertThat(jobListingRepository.findById(jobId).orElseThrow().description())
                .as("a null incoming description must not blank an existing stored one")
                .isEqualTo("Original description");

        // A non-null incoming description does replace the stored one.
        JobCardInsert refetchWithNewDescription = new JobCardInsert("lever", "abc-123", "Java Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://lever.co/jobs/view/abc-123", "https://lever.co/company/acme", "Updated description");
        jobListingRepository.upsertAll(List.of(refetchWithNewDescription), runId, Instant.now());
        assertThat(jobListingRepository.findById(jobId).orElseThrow().description())
                .isEqualTo("Updated description");
    }

    @Test
    void firstSeenAtIsImmutableAcrossUpserts_lastSeenFieldsUpdate() {
        Instant firstRunTime = Instant.parse("2026-08-20T10:00:00Z");
        Instant secondRunTime = Instant.parse("2026-08-27T15:30:00Z");

        long runId1 = sweepRunRepository.create(firstRunTime, "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(42, "Java Engineer")), runId1, firstRunTime);

        long jobId = jobIdFor("42");
        JobListing afterFirstInsert = jobListingRepository
                .findByRunAndVerdictAndUserStatus(runId1, null, null)
                .stream().filter(j -> j.jobId() == jobId).findFirst().orElseThrow();
        assertThat(afterFirstInsert.firstSeenAt()).isEqualTo(firstRunTime);
        assertThat(afterFirstInsert.lastSeenAt()).isEqualTo(firstRunTime);
        assertThat(afterFirstInsert.lastSeenRunId()).isEqualTo(runId1);

        long runId2 = sweepRunRepository.create(secondRunTime, "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(42, "Java Engineer")), runId2, secondRunTime);

        JobListing afterSecondUpsert = jobListingRepository
                .findByRunAndVerdictAndUserStatus(runId2, null, null)
                .stream().filter(j -> j.jobId() == jobId).findFirst().orElseThrow();

        assertThat(afterSecondUpsert.firstSeenAt())
                .as("first_seen_at must never change on a re-upsert of the same job")
                .isEqualTo(firstRunTime);
        assertThat(afterSecondUpsert.lastSeenAt()).isEqualTo(secondRunTime);
        assertThat(afterSecondUpsert.lastSeenRunId()).isEqualTo(runId2);
    }

    @Test
    void applySalaryWritesBandAndSourceOntoTheJob() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(99, "Java Engineer")), runId, Instant.now());
        long jobId = jobIdFor("99");

        int updated = jobListingRepository.applySalary(jobId, 150000.0, 190000.0, "lca", "ACME CORP LLC");
        assertThat(updated).isEqualTo(1);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salaryMin()).isEqualTo(150000.0);
        assertThat(job.salaryMax()).isEqualTo(190000.0);
        assertThat(job.salarySource()).isEqualTo("lca");
        assertThat(job.salarySourceDetail()).isEqualTo("ACME CORP LLC");

        assertThat(jobListingRepository.applySalary(jobId + 12345, 1.0, 2.0, "lca", null)).isEqualTo(0);
    }

    @Test
    void findPassingWithoutSalaryByRunReturnsOnlyPassingSalarylessRowsOfThatRun() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(
                card(1, "Java Engineer"), card(2, "Backend Engineer"),
                card(3, "Platform Engineer"), card(4, "Data Engineer")), runId, Instant.now());
        long job1 = jobIdFor("1");
        long job2 = jobIdFor("2");
        long job3 = jobIdFor("3");

        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(job1, FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(job2, FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(job3, FilterVerdict.REJECT, "title_word:x")), 1);
        // job 4 keeps a null verdict; job 2 already has a salary
        jobListingRepository.applySalary(job2, 100000.0, 120000.0, "lca", null);

        // a passing, salaryless row that belongs to a *different* run must not appear
        long otherRun = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(9, "Java Engineer 9")), otherRun, Instant.now());
        long job9 = jobIdFor("9");
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(job9, FilterVerdict.PASS, null)), 1);

        List<Long> ids = jobListingRepository.findPassingWithoutSalaryByRun(runId)
                .stream().map(JobListing::jobId).toList();
        assertThat(ids).containsExactly(job1);
    }

    @Test
    void userStatusRoundTripsThroughAppliedQuery() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(7, "Java Engineer")), runId, Instant.now());
        long jobId = jobIdFor("7");

        Instant statusAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int updated = jobListingRepository.setUserStatus(jobId, UserStatus.APPLIED, statusAt);
        assertThat(updated).isEqualTo(1);

        List<JobListing> applied = jobListingRepository.findByUserStatus(UserStatus.APPLIED);
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).jobId()).isEqualTo(jobId);
        assertThat(applied.get(0).userStatus()).isEqualTo(UserStatus.APPLIED);
        assertThat(applied.get(0).userStatusAt()).isEqualTo(statusAt);

        assertThat(jobListingRepository.findByUserStatus(UserStatus.NOT_INTERESTED)).isEmpty();
    }
}
