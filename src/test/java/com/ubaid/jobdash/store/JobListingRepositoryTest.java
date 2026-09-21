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

    // ---- the location-visibility rule --------------------------------------------------------

    private long insertPassingJob(long runId, String sourceJobId, Integer locationUs, Integer confident) {
        jobListingRepository.upsertAll(List.of(new JobCardInsert("workday", sourceJobId, "Engineer", "Acme Corp",
                "Somewhere", Instant.parse("2026-08-27T00:00:00Z"), "https://x/" + sourceJobId, null, "a description")),
                runId, Instant.parse("2026-08-28T00:00:00Z"));
        long jobId = jobIdFor(sourceJobId);
        client.sql("""
                        update job_listing
                        set filter_verdict = 'pass', filter_version = 1,
                            location_us = :us, location_confident = :c
                        where job_id = :id
                        """)
                .param("us", locationUs)
                .param("c", confident)
                .param("id", jobId)
                .update();
        return jobId;
    }

    /**
     * The visibility rule, asserted across every query that enforces it.
     *
     * <p>The NULL case is the one that matters most. SQLite comparisons against NULL yield NULL,
     * so a predicate written as {@code not (location_us = 0 and location_confident = 1)} evaluates
     * to NULL for an unclassified row — and {@code WHERE} drops it. That silently hid every row
     * the classifier had not reached yet, which is the precise opposite of the intent. The
     * {@code coalesce} in those queries is what makes this test pass; do not remove it.
     */
    @Test
    void onlyConfidentlyNonUsRowsAreHiddenFromEveryReadPath() {
        long runId = sweepRunRepository.create(Instant.parse("2026-08-28T00:00:00Z"), "engineer", "", 24,
                false, null, "workday", null);

        long unclassified = insertPassingJob(runId, "JR-null", null, null);
        long confidentUs = insertPassingJob(runId, "JR-us", 1, 1);
        long lowConfidence = insertPassingJob(runId, "JR-lowconf", 0, 0);
        long confidentForeign = insertPassingJob(runId, "JR-foreign", 0, 1);

        assertThat(jobListingRepository.findByRunAndVerdictAndNullUserStatus(runId, FilterVerdict.PASS))
                .extracting(JobListing::jobId)
                .as("search tab")
                .containsExactlyInAnyOrder(unclassified, confidentUs, lowConfidence)
                .doesNotContain(confidentForeign);

        assertThat(jobListingRepository.findByVerdictAndNullUserStatus(FilterVerdict.PASS))
                .extracting(JobListing::jobId)
                .as("search tab, all runs")
                .containsExactlyInAnyOrder(unclassified, confidentUs, lowConfidence);

        assertThat(jobListingRepository.findScannableByRun(runId))
                .extracting(JobListing::jobId)
                .as("AI scan")
                .containsExactlyInAnyOrder(unclassified, confidentUs, lowConfidence);

        assertThat(jobListingRepository.findScannableByIds(
                        List.of(unclassified, confidentUs, lowConfidence, confidentForeign)))
                .extracting(JobListing::jobId)
                .as("AI re-scan by id")
                .containsExactlyInAnyOrder(unclassified, confidentUs, lowConfidence);

        assertThat(jobListingRepository.findPassingWithoutSalaryByRun(runId))
                .extracting(JobListing::jobId)
                .as("salary enrichment")
                .containsExactlyInAnyOrder(unclassified, confidentUs, lowConfidence);
    }

    @Test
    void applyLocationVerdictStampsOnlyTheMatchingRowsOfThatRun() {
        long runA = sweepRunRepository.create(Instant.parse("2026-08-28T00:00:00Z"), "e", "", 24,
                false, null, "workday", null);
        long runB = sweepRunRepository.create(Instant.parse("2026-08-28T00:00:00Z"), "e", "", 24,
                false, null, "workday", null);
        jobListingRepository.upsertAll(List.of(new JobCardInsert("workday", "A1", "Engineer", "Acme",
                "Israel, Yokneam", Instant.parse("2026-08-27T00:00:00Z"), "https://x/A1", null, "d")),
                runA, Instant.parse("2026-08-28T00:00:00Z"));
        jobListingRepository.upsertAll(List.of(new JobCardInsert("workday", "B1", "Engineer", "Acme",
                "Israel, Yokneam", Instant.parse("2026-08-27T00:00:00Z"), "https://x/B1", null, "d")),
                runB, Instant.parse("2026-08-28T00:00:00Z"));

        int updated = jobListingRepository.applyLocationVerdict(runA, "Israel, Yokneam", false, true);

        assertThat(updated).isEqualTo(1);
        assertThat(jobListingRepository.findById(jobIdFor("A1")).orElseThrow().locationUs()).isFalse();
        assertThat(jobListingRepository.findById(jobIdFor("B1")).orElseThrow().locationUs())
                .as("another run's identical location must not be judged by this run")
                .isNull();
    }

    @Test
    void findDistinctLocationsByRunDeduplicatesAndSkipsBlanks() {
        long runId = sweepRunRepository.create(Instant.parse("2026-08-28T00:00:00Z"), "e", "", 24,
                false, null, "workday", null);
        for (String[] pair : new String[][]{{"D1", "Austin, TX"}, {"D2", "Austin, TX"}, {"D3", "  "}}) {
            jobListingRepository.upsertAll(List.of(new JobCardInsert("workday", pair[0], "Engineer", "Acme",
                    pair[1], Instant.parse("2026-08-27T00:00:00Z"), "https://x/" + pair[0], null, "d")),
                    runId, Instant.parse("2026-08-28T00:00:00Z"));
        }

        assertThat(jobListingRepository.findDistinctLocationsByRun(runId)).containsExactly("Austin, TX");
    }

    // --- detail-fetch queue ---------------------------------------------------

    @Test
    void detailQueueReturnsUnfetchedPassingUntriagedLinkedInRowsAcrossRuns_newestFirst() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        long otherRun = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        Instant older = Instant.parse("2026-09-01T00:00:00Z");
        Instant newer = Instant.parse("2026-09-08T00:00:00Z");
        jobListingRepository.upsertAll(List.of(
                new JobCardInsert("linkedin", "1", "Older", "Acme", "NY", older, "u1", null, null),
                new JobCardInsert("linkedin", "2", "Newer", "Acme", "NY", newer, "u2", null, null),
                new JobCardInsert("linkedin", "3", "Rejected", "Acme", "NY", newer, "u3", null, null),
                new JobCardInsert("linkedin", "4", "Applied", "Acme", "NY", newer, "u4", null, null),
                new JobCardInsert("linkedin", "5", "Fetched", "Acme", "NY", newer, "u5", null, null),
                new JobCardInsert("lever", "6", "Lever", "Acme", "NY", newer, "u6", null, "has one")),
                runId, Instant.now());
        jobListingRepository.upsertAll(List.of(
                new JobCardInsert("linkedin", "7", "Other run", "Acme", "NY", newer, "u7", null, null)),
                otherRun, Instant.now());
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(jobIdFor("1"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("2"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("3"), FilterVerdict.REJECT, "senior"),
                new JobListingRepository.VerdictUpdate(jobIdFor("4"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("5"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("6"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("7"), FilterVerdict.PASS, null)), 1);
        jobListingRepository.setUserStatus(jobIdFor("4"), UserStatus.APPLIED, Instant.now());
        jobListingRepository.applyDetail(jobIdFor("5"), "already have it", "hash5", null, Instant.now());

        List<JobListing> queue = jobListingRepository.findDetailQueue(10);

        // Row 7 belongs to another run and is still queued: leftovers are never stranded.
        assertThat(queue).extracting(JobListing::sourceJobId).containsExactly("7", "2", "1");
        assertThat(jobListingRepository.findDetailQueue(1))
                .extracting(JobListing::sourceJobId).containsExactly("7");
    }

    @Test
    void applyDetailPopulatesTheRowAndDequeuesIt_markGoneDequeuesWithoutADescription() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(10, "A"), card(11, "B")), runId, Instant.now());
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(jobIdFor("10"), FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(jobIdFor("11"), FilterVerdict.PASS, null)), 1);
        Instant at = Instant.parse("2026-09-09T12:00:00Z");

        assertThat(jobListingRepository.applyDetail(jobIdFor("10"), "Full text", "abc123", "onsite", at)).isEqualTo(1);
        assertThat(jobListingRepository.markDetailGone(jobIdFor("11"), at)).isEqualTo(1);

        JobListing fetched = jobListingRepository.findById(jobIdFor("10")).orElseThrow();
        assertThat(fetched.description()).isEqualTo("Full text");
        assertThat(fetched.descriptionHash()).isEqualTo("abc123");
        assertThat(fetched.applyKind()).isEqualTo("onsite");
        assertThat(fetched.detailStatus()).isEqualTo("ok");
        assertThat(fetched.detailFetchedAt()).isEqualTo(at);

        JobListing gone = jobListingRepository.findById(jobIdFor("11")).orElseThrow();
        assertThat(gone.description()).isNull();
        assertThat(gone.detailStatus()).isEqualTo("gone");
        assertThat(gone.detailFetchedAt()).isEqualTo(at);

        assertThat(jobListingRepository.findDetailQueue(10)).isEmpty();
        // Only the fetched row is now scannable - the gone row still has no description.
        assertThat(jobListingRepository.findScannableByRun(runId))
                .extracting(JobListing::sourceJobId).containsExactly("10");
    }

    // --- LinkedIn -> ATS apply target -------------------------------------------------------

    @Test
    void setApplyTargetRoundTrips() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(20, "A")), runId, Instant.now());
        long jobId = jobIdFor("20");

        int updated = jobListingRepository.setApplyTarget(jobId,
                "https://job-boards.greenhouse.io/embed/job_app?for=acme&token=123", "greenhouse",
                "description 0.80 (next 0.51)");
        assertThat(updated).isEqualTo(1);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.applyUrl()).isEqualTo("https://job-boards.greenhouse.io/embed/job_app?for=acme&token=123");
        assertThat(job.applyDomain()).isEqualTo("greenhouse");
        assertThat(job.applyMatchNote()).isEqualTo("description 0.80 (next 0.51)");
    }

    @Test
    void upsertAllReUpsertingTheSameRowDoesNotClearApplyUrlDomainOrKind() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(22, "A")), runId, Instant.now());
        long jobId = jobIdFor("22");
        jobListingRepository.applyDetail(jobId, "desc", "hash22", "onsite", Instant.now());
        jobListingRepository.setApplyTarget(jobId, "https://jobs.lever.co/acme/xyz/apply", "lever", "req id R1");

        // A later sweep re-upserts the same (source, source_job_id) row.
        long runId2 = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(22, "A")), runId2, Instant.now());

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.applyUrl()).as("apply_url must survive a re-upsert")
                .isEqualTo("https://jobs.lever.co/acme/xyz/apply");
        assertThat(job.applyDomain()).as("apply_domain must survive a re-upsert").isEqualTo("lever");
        assertThat(job.applyKind()).as("apply_kind must survive a re-upsert").isEqualTo("onsite");
    }
}
