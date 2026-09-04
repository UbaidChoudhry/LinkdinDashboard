package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.SalaryEstimate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryEnrichmentServiceTest extends AbstractStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    private final java.time.Clock clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    private SalaryProperties props(boolean enabled) {
        return new SalaryProperties(enabled, Duration.ofDays(90), Duration.ofDays(1095),
                new SalaryProperties.Pacing(Duration.ofSeconds(1)),
                new SalaryProperties.DailyCap(200, 100),
                new SalaryProperties.MonthlyCap(0, 0),
                new SalaryProperties.Adzuna("", ""),
                new SalaryProperties.H1bApi(""),
                new SalaryProperties.Lca("", true));
    }

    /** In-test source returning a canned result (or throwing). */
    private static final class FakeSource implements SalarySource {
        private final String name;
        private final SalaryResult result;
        private final boolean throwing;

        FakeSource(String name, SalaryResult result, boolean throwing) {
            this.name = name;
            this.result = result;
            this.throwing = throwing;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<SalaryResult> lookup(SalaryLookup q) {
            if (throwing) {
                throw new IllegalStateException("boom");
            }
            return Optional.ofNullable(result);
        }
    }

    private SalaryEnrichmentService service(boolean enabled, SalarySource... sources) {
        return new SalaryEnrichmentService(List.of(sources), salaryEstimateRepository,
                jobListingRepository, props(enabled), clock);
    }

    /** Seeds one passing job and returns the sweep run id it belongs to. */
    private long seedPassingJob(long sourceJobId, String company, String title) {
        long runId = sweepRunRepository.create(NOW, "swe", "", 24, false, null);
        jobListingRepository.upsertAll(List.of(new JobCardInsert("linkedin", String.valueOf(sourceJobId), title, company,
                "Austin, Texas", NOW, "https://linkedin.com/jobs/view/" + sourceJobId,
                "https://linkedin.com/company/x", null)), runId, NOW);
        client.sql("update job_listing set filter_verdict = 'pass' where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId)).update();
        return runId;
    }

    /** Resolves a seeded card's actual (autoincrement surrogate) job_id by its source_job_id. */
    private long jobIdFor(long sourceJobId) {
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
    }

    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    @Test
    void cacheHitFreshAppliesWithoutCallingAnySource() {
        long runId = seedPassingJob(1, "Acme Robotics", "Software Engineer");
        salaryEstimateRepository.upsert(new SalaryEstimate("acme robotics", "software engineer",
                120000.0, 155000.0, "USD", "adzuna", "2026-01-01", 5, NOW.minus(Duration.ofDays(10)), null));

        FakeSource shouldNotRun = new FakeSource("lca",
                new SalaryResult(1.0, 2.0, "USD", LocalDate.now(clock), 1, "lca", null), false);
        service(true, shouldNotRun).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(1)).orElseThrow();
        assertThat(job.salaryMax()).isEqualTo(155000.0);
        assertThat(job.salarySource()).isEqualTo("adzuna");
    }

    @Test
    void cacheHitReAppliesSourceDetailOntoTheJob() {
        long runId = seedPassingJob(7, "Acme Robotics", "Software Engineer");
        salaryEstimateRepository.upsert(new SalaryEstimate("acme robotics", "software engineer",
                160000.0, 205000.0, "USD", "lca", "2025-03-01", 12, NOW.minus(Duration.ofDays(10)),
                "ACME ROBOTICS LLC"));

        // No source should run on a fresh cache hit; the detail must still land on the job.
        service(true, new FakeSource("lca", null, true)).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(7)).orElseThrow();
        assertThat(job.salarySource()).isEqualTo("lca");
        assertThat(job.salarySourceDetail()).isEqualTo("ACME ROBOTICS LLC");
    }

    @Test
    void cacheMissRunsCascadeAndWritesEstimateAndJob() {
        long runId = seedPassingJob(2, "Acme Robotics", "Software Engineer");
        FakeSource lca = new FakeSource("lca",
                new SalaryResult(160000.0, 210000.0, "USD", LocalDate.of(2025, 3, 1), 12, "lca",
                        "ACME ROBOTICS LLC"), false);

        service(true, lca).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(2)).orElseThrow();
        assertThat(job.salaryMin()).isEqualTo(160000.0);
        assertThat(job.salaryMax()).isEqualTo(210000.0);
        assertThat(job.salarySource()).isEqualTo("lca");
        assertThat(job.salarySourceDetail()).isEqualTo("ACME ROBOTICS LLC");

        SalaryEstimate cached = salaryEstimateRepository.find("acme robotics", "software engineer").orElseThrow();
        assertThat(cached.source()).isEqualTo("lca");
        assertThat(cached.dataDate()).isEqualTo("2025-03-01");
        assertThat(cached.fetchedAt()).isEqualTo(NOW);
        assertThat(cached.sourceDetail()).isEqualTo("ACME ROBOTICS LLC");
    }

    @Test
    void resultOlderThanMaxDataAgeIsRejectedAndNextSourceTried() {
        long runId = seedPassingJob(3, "Acme Robotics", "Software Engineer");
        FakeSource stale = new FakeSource("lca",
                new SalaryResult(90000.0, 100000.0, "USD", LocalDate.of(2021, 1, 1), 3, "lca", null), false);
        FakeSource fresh = new FakeSource("adzuna",
                new SalaryResult(150000.0, 175000.0, "USD", LocalDate.of(2026, 6, 1), 8, "adzuna", null), false);

        service(true, fresh, stale).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(3)).orElseThrow();
        assertThat(job.salarySource()).isEqualTo("adzuna");
        assertThat(job.salaryMax()).isEqualTo(175000.0);
    }

    @Test
    void totalMissWritesNoneAndLeavesJobSalaryNull() {
        long runId = seedPassingJob(4, "Acme Robotics", "Software Engineer");
        FakeSource empty = new FakeSource("lca", null, false);

        service(true, empty).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(4)).orElseThrow();
        assertThat(job.salarySource()).isNull();
        assertThat(job.salaryMax()).isNull();

        SalaryEstimate cached = salaryEstimateRepository.find("acme robotics", "software engineer").orElseThrow();
        assertThat(cached.source()).isEqualTo("none");
        assertThat(cached.salaryMin()).isNull();
        assertThat(cached.dataDate()).isNull();
    }

    @Test
    void cancelledStopsMidLoop() {
        long runId = sweepRunRepository.create(NOW, "swe", "", 24, false, null);
        for (long id : new long[]{10, 11}) {
            jobListingRepository.upsertAll(List.of(new JobCardInsert("linkedin", String.valueOf(id), "Software Engineer " + id, "Acme Robotics",
                    "Austin, Texas", NOW, "u", "c", null)), runId, NOW);
            client.sql("update job_listing set filter_verdict = 'pass' where source_job_id = :id")
                    .param("id", String.valueOf(id)).update();
        }
        FakeSource lca = new FakeSource("lca",
                new SalaryResult(1.0, 200000.0, "USD", LocalDate.of(2025, 1, 1), 1, "lca", null), false);

        boolean[] first = {true};
        BooleanSupplier cancelAfterFirst = () -> {
            if (first[0]) {
                first[0] = false;
                return false;
            }
            return true;
        };
        service(true, lca).enrichRun(runId, "Austin, Texas", cancelAfterFirst);

        long enrichedCount = client.sql("select count(*) from job_listing where salary_source is not null")
                .query(Long.class).single();
        assertThat(enrichedCount).isEqualTo(1);
    }

    @Test
    void throwingSourceIsSwallowedAndRunCompletes() {
        long runId = seedPassingJob(5, "Acme Robotics", "Software Engineer");
        FakeSource bad = new FakeSource("lca", null, true);

        service(true, bad).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        JobListing job = jobListingRepository.findById(jobIdFor(5)).orElseThrow();
        assertThat(job.salarySource()).isNull();
        // A throwing source is treated as "no result" -> a 'none' estimate is still cached.
        assertThat(salaryEstimateRepository.find("acme robotics", "software engineer").orElseThrow().source())
                .isEqualTo("none");
    }

    @Test
    void disabledIsANoOp() {
        long runId = seedPassingJob(6, "Acme Robotics", "Software Engineer");
        FakeSource lca = new FakeSource("lca",
                new SalaryResult(1.0, 2.0, "USD", LocalDate.of(2025, 1, 1), 1, "lca", null), false);

        service(false, lca).enrichRun(runId, "Austin, Texas", NOT_CANCELLED);

        assertThat(jobListingRepository.findById(jobIdFor(6)).orElseThrow().salarySource()).isNull();
        assertThat(salaryEstimateRepository.find("acme robotics", "software engineer")).isEmpty();
    }
}
