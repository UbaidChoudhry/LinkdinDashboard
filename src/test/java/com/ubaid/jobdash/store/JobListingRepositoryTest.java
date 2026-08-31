package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobListingRepositoryTest extends AbstractStoreTest {

    private static JobCardInsert card(long jobId, String title) {
        return new JobCardInsert(jobId, title, "Acme Corp", "Remote",
                Instant.parse("2026-08-27T00:00:00Z"),
                "https://linkedin.com/jobs/view/" + jobId, "https://linkedin.com/company/acme");
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
    void firstSeenAtIsImmutableAcrossUpserts_lastSeenFieldsUpdate() {
        Instant firstRunTime = Instant.parse("2026-08-20T10:00:00Z");
        Instant secondRunTime = Instant.parse("2026-08-27T15:30:00Z");

        long runId1 = sweepRunRepository.create(firstRunTime, "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(42, "Java Engineer")), runId1, firstRunTime);

        JobListing afterFirstInsert = jobListingRepository
                .findByRunAndVerdictAndUserStatus(runId1, null, null)
                .stream().filter(j -> j.jobId() == 42).findFirst().orElseThrow();
        assertThat(afterFirstInsert.firstSeenAt()).isEqualTo(firstRunTime);
        assertThat(afterFirstInsert.lastSeenAt()).isEqualTo(firstRunTime);
        assertThat(afterFirstInsert.lastSeenRunId()).isEqualTo(runId1);

        long runId2 = sweepRunRepository.create(secondRunTime, "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(42, "Java Engineer")), runId2, secondRunTime);

        JobListing afterSecondUpsert = jobListingRepository
                .findByRunAndVerdictAndUserStatus(runId2, null, null)
                .stream().filter(j -> j.jobId() == 42).findFirst().orElseThrow();

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

        int updated = jobListingRepository.applySalary(99, 150000.0, 190000.0, "lca", "ACME CORP LLC");
        assertThat(updated).isEqualTo(1);

        JobListing job = jobListingRepository.findById(99).orElseThrow();
        assertThat(job.salaryMin()).isEqualTo(150000.0);
        assertThat(job.salaryMax()).isEqualTo(190000.0);
        assertThat(job.salarySource()).isEqualTo("lca");
        assertThat(job.salarySourceDetail()).isEqualTo("ACME CORP LLC");

        assertThat(jobListingRepository.applySalary(12345, 1.0, 2.0, "lca", null)).isEqualTo(0);
    }

    @Test
    void findPassingWithoutSalaryByRunReturnsOnlyPassingSalarylessRowsOfThatRun() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(
                card(1, "Java Engineer"), card(2, "Backend Engineer"),
                card(3, "Platform Engineer"), card(4, "Data Engineer")), runId, Instant.now());

        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(1, com.ubaid.jobdash.domain.FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(2, com.ubaid.jobdash.domain.FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(3, com.ubaid.jobdash.domain.FilterVerdict.REJECT, "title_word:x")), 1);
        // job 4 keeps a null verdict; job 2 already has a salary
        jobListingRepository.applySalary(2, 100000.0, 120000.0, "lca", null);

        // a passing, salaryless row that belongs to a *different* run must not appear
        long otherRun = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(9, "Java Engineer 9")), otherRun, Instant.now());
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(9, com.ubaid.jobdash.domain.FilterVerdict.PASS, null)), 1);

        List<Long> ids = jobListingRepository.findPassingWithoutSalaryByRun(runId)
                .stream().map(JobListing::jobId).toList();
        assertThat(ids).containsExactly(1L);
    }

    @Test
    void userStatusRoundTripsThroughAppliedQuery() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(7, "Java Engineer")), runId, Instant.now());

        Instant statusAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int updated = jobListingRepository.setUserStatus(7, UserStatus.APPLIED, statusAt);
        assertThat(updated).isEqualTo(1);

        List<JobListing> applied = jobListingRepository.findByUserStatus(UserStatus.APPLIED);
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).jobId()).isEqualTo(7);
        assertThat(applied.get(0).userStatus()).isEqualTo(UserStatus.APPLIED);
        assertThat(applied.get(0).userStatusAt()).isEqualTo(statusAt);

        assertThat(jobListingRepository.findByUserStatus(UserStatus.NOT_INTERESTED)).isEmpty();
    }
}
