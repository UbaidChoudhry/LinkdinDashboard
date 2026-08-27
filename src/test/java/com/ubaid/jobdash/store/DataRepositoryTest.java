package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataRepositoryTest extends AbstractStoreTest {

    private DataRepository dataRepository;
    private DatabaseFileLocator fileLocator;

    private long seedJobsAndRuns() {
        long runId = sweepRunRepository.create(Instant.parse("2026-08-20T10:00:00Z"), "java", "remote", 24, false, null);

        Instant t0 = Instant.parse("2026-08-20T10:00:00Z");
        Instant t1 = Instant.parse("2026-08-27T15:00:00Z");

        jobListingRepository.upsertAll(List.of(
                new JobCardInsert(1, "Java Engineer", "Acme", "Remote", t0,
                        "https://linkedin.com/jobs/view/1", null),
                new JobCardInsert(2, "Senior Java Engineer", "Acme", "Remote", t1,
                        "https://linkedin.com/jobs/view/2", null),
                new JobCardInsert(3, "Backend Engineer", "Blocked Co", "Remote", t1,
                        "https://linkedin.com/jobs/view/3", null)
        ), runId, t0);

        // job 1: passing, untriaged
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(1, FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(2, FilterVerdict.REJECT, "title_word:Senior"),
                new JobListingRepository.VerdictUpdate(3, FilterVerdict.PASS, null)
        ), 1);
        jobListingRepository.setUserStatus(1, UserStatus.APPLIED, t1);
        jobListingRepository.setUserStatus(3, UserStatus.NOT_INTERESTED, t1);
        // job 2 (rejected) is left untriaged for user status.

        return runId;
    }

    @Test
    void jobStatsCountsEachBucketCorrectly() {
        seedJobsAndRuns();
        dataRepository = new DataRepository(client);

        DataRepository.JobStats stats = dataRepository.jobStats();

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.passCount()).isEqualTo(2);
        assertThat(stats.rejectCount()).isEqualTo(1);
        assertThat(stats.unevaluatedCount()).isEqualTo(0);
        assertThat(stats.appliedCount()).isEqualTo(1);
        assertThat(stats.notInterestedCount()).isEqualTo(1);
        assertThat(stats.untriagedCount()).isEqualTo(1);
        assertThat(stats.oldestFirstSeenAt()).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
        assertThat(stats.newestLastSeenAt()).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
    }

    @Test
    void jobStatsOnEmptyDatabaseIsAllZerosNotNull() {
        dataRepository = new DataRepository(client);

        DataRepository.JobStats stats = dataRepository.jobStats();

        assertThat(stats.total()).isZero();
        assertThat(stats.passCount()).isZero();
        assertThat(stats.rejectCount()).isZero();
        assertThat(stats.oldestFirstSeenAt()).isNull();
        assertThat(stats.newestLastSeenAt()).isNull();
    }

    @Test
    void countsCoverRunsRequestLogAndFilterLists() {
        long runId = seedJobsAndRuns();
        requestLogRepository.append(runId, Instant.now(), "https://example.com", 200, "ok", 100, 8000);
        dataRepository = new DataRepository(client);

        assertThat(dataRepository.countRuns()).isEqualTo(1);
        assertThat(dataRepository.countRequestLogEntries()).isEqualTo(1);
        assertThat(dataRepository.countExcludeWords()).isEqualTo(8); // seeded defaults
        assertThat(dataRepository.countBlockedCompanies()).isEqualTo(0);
    }

    @Test
    void clearJobResultsRemovesJobsAndRunsButKeepsRequestLogAndFilterLists() {
        long runId = seedJobsAndRuns();
        requestLogRepository.append(runId, Instant.now(), "https://example.com", 200, "ok", 100, 8000);
        companyBlocklistRepository.add("Blocked Co", "manual", null, Instant.now());
        dataRepository = new DataRepository(client);

        DataRepository.ClearResult result = dataRepository.clearJobResults();

        assertThat(result.jobsCleared()).isEqualTo(3);
        assertThat(result.runsCleared()).isEqualTo(1);
        assertThat(dataRepository.jobStats().total()).isZero();
        assertThat(dataRepository.countRuns()).isZero();

        // Deliberately untouched: the rate-limiter's audit trail and user configuration.
        assertThat(dataRepository.countRequestLogEntries())
                .as("clearing job results must not reset the rolling request budget's audit trail")
                .isEqualTo(1);
        assertThat(dataRepository.countExcludeWords()).isEqualTo(8);
        assertThat(dataRepository.countBlockedCompanies()).isEqualTo(1);
    }

    @Test
    void clearJobResultsOnEmptyDatabaseReturnsZerosAndDoesNotThrow() {
        dataRepository = new DataRepository(client);

        DataRepository.ClearResult result = dataRepository.clearJobResults();

        assertThat(result.jobsCleared()).isZero();
        assertThat(result.runsCleared()).isZero();
    }

    @Test
    void vacuumRunsWithoutThrowingAfterAClear() {
        seedJobsAndRuns();
        dataRepository = new DataRepository(client);
        dataRepository.clearJobResults();

        dataRepository.vacuum();
        // No exception is the assertion: VACUUM must be able to run immediately after a
        // transactional delete completes, since SQLite forbids VACUUM inside a transaction.
    }

    @Test
    void databaseFileLocatorReportsANonZeroSizeForARealDatabase() {
        String url = "jdbc:sqlite:" + tempDir.resolve("jobdash-test.db");
        fileLocator = new DatabaseFileLocator(url);
        seedJobsAndRuns();

        assertThat(fileLocator.sizeBytes()).isGreaterThan(0);
    }

    @Test
    void databaseFileLocatorStripsQueryParamsFromTheUrl() {
        String url = "jdbc:sqlite:" + tempDir.resolve("jobdash-test.db") + "?journal_mode=WAL";
        fileLocator = new DatabaseFileLocator(url);

        assertThat(fileLocator.databaseFile().toString()).doesNotContain("?");
        assertThat(fileLocator.databaseFile().toString()).endsWith("jobdash-test.db");
    }
}
