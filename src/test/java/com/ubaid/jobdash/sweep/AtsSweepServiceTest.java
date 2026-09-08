package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.salary.SalaryEnrichmentService;
import com.ubaid.jobdash.source.JobSource;
import com.ubaid.jobdash.source.SourceFetchResult;
import com.ubaid.jobdash.source.SourceQuery;
import com.ubaid.jobdash.source.SourcedJob;
import com.ubaid.jobdash.source.ats.AtsProperties;
import com.ubaid.jobdash.source.workday.WorkdaySiteResolver;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link AtsSweepService} against fake {@link JobSource} implementations - no HTTP, no
 * network - and a real, temporary SQLite database (see {@link AbstractStoreTest}) for
 * {@code ats_company} / {@code job_listing}, so the actual dead-slug and upsert SQL is exercised
 * rather than mocked away.
 */
@ExtendWith(MockitoExtension.class)
class AtsSweepServiceTest extends AbstractStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private AtsCompanyRepository atsCompanyRepository;
    private FilterEngine filterEngine;
    private Clock clock;

    @Mock
    private WorkdaySiteResolver workdaySiteResolver;

    @BeforeEach
    void setUpAdditional() {
        atsCompanyRepository = new AtsCompanyRepository(client);
        filterEngine = new FilterEngine(excludeWordRepository, companyBlocklistRepository,
                filterStateRepository, jobListingRepository);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // The seed data (V6) ships 42 pre-enabled companies; disable them all so each test
        // controls exactly which companies findForRun sees.
        for (AtsCompany c : atsCompanyRepository.list(null, null, false, 1000, 0)) {
            atsCompanyRepository.setEnabled(c.id(), false);
        }
    }

    private AtsSweepService newService(JobSource... sources) {
        AtsProperties properties = new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)),
                150, new AtsProperties.Workday(50, 20), new AtsProperties.DailyCap(2000, 2000, 2000),
                2, 33_554_432L, new AtsProperties.Slugs("", false));
        return new AtsSweepService(List.of(sources), atsCompanyRepository, workdaySiteResolver,
                jobListingRepository, filterEngine, new NoopEnrichment(), sweepRunRepository,
                new RunProgressRegistry(), properties, clock);
    }

    private long newRun() {
        return sweepRunRepository.create(NOW, "engineer", "remote", 24, false, null);
    }

    private long addCompany(String ats, String slug, String company, String host, String site) {
        return atsCompanyRepository.insertOne(ats, slug, company, host, site, true, "active", NOW).orElseThrow();
    }

    // --- scenarios ------------------------------------------------------------------------

    @Test
    void emptyCompanyListYieldsNoSources() {
        AtsSweepService service = newService(new FakeJobSource("greenhouse"));
        long runId = newRun();

        // No company in ats_company has this ats name - findForRun legitimately returns empty.
        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("nonexistent-ats"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("no_sources");
    }

    @Test
    void deadSlugRecordsFailureAndTheRunContinuesToTheNextCompany() {
        long dead = addCompany("greenhouse", "deadco", "DeadCo", null, null);
        long alive = addCompany("greenhouse", "aliveco", "AliveCo", null, null);

        SourcedJob job = sourcedJob("greenhouse", "AliveCo");
        FakeJobSource greenhouse = new FakeJobSource("greenhouse",
                new SourceFetchResult.DeadSlug("board not found"),
                new SourceFetchResult.Ok(List.of(job), 1));

        AtsSweepService service = newService(greenhouse);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("greenhouse"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        AtsCompany deadRow = atsCompanyRepository.findById(dead).orElseThrow();
        assertThat(deadRow.consecutiveFailures()).isEqualTo(1);
        AtsCompany aliveRow = atsCompanyRepository.findById(alive).orElseThrow();
        assertThat(aliveRow.consecutiveFailures()).isEqualTo(0);
        assertThat(countJobsForSource("greenhouse")).isEqualTo(1);
    }

    @Test
    void transportErrorOnOneCompanyDoesNotAbortOthers() {
        long broken = addCompany("greenhouse", "brokenco", "BrokenCo", null, null);
        long alive = addCompany("greenhouse", "aliveco", "AliveCo", null, null);

        SourcedJob job = sourcedJob("greenhouse", "AliveCo");
        FakeJobSource greenhouse = new FakeJobSource("greenhouse",
                new SourceFetchResult.TransportError("connection reset"),
                new SourceFetchResult.Ok(List.of(job), 1));

        AtsSweepService service = newService(greenhouse);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("greenhouse"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        assertThat(atsCompanyRepository.findById(broken).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(atsCompanyRepository.findById(alive).orElseThrow().consecutiveFailures()).isEqualTo(0);
        assertThat(countJobsForSource("greenhouse")).isEqualTo(1);
    }

    @Test
    void jobsAreUpsertedWithSourceAndSourceJobIdAndDescription() {
        addCompany("lever", "acme", "Acme", null, null);
        SourcedJob job = new SourcedJob("lever", "job-abc-123", "Backend Engineer", "Acme", "Remote",
                Instant.parse("2026-09-01T00:00:00Z"), "https://jobs.lever.co/acme/job-abc-123", null,
                "We are hiring a backend engineer.");
        FakeJobSource lever = new FakeJobSource("lever", new SourceFetchResult.Ok(List.of(job), 1));

        AtsSweepService service = newService(lever);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("lever"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        var stored = client.sql("select * from job_listing where source = 'lever' and source_job_id = 'job-abc-123'")
                .query(JobListingRepositoryTestRowMapper::map)
                .single();
        assertThat(stored.description()).isEqualTo("We are hiring a backend engineer.");
    }

    @Test
    void resolvedWorkdaySiteIsCachedViaUpsertSite() {
        long companyId = addCompany("workday", "acme", "Acme", "acme.wd5.myworkdayjobs.com", null);
        when(workdaySiteResolver.resolve("acme.wd5.myworkdayjobs.com")).thenReturn(Optional.of("AcmeCareers"));

        FakeJobSource workday = new FakeJobSource("workday", new SourceFetchResult.Ok(List.of(), 1));
        AtsSweepService service = newService(workday);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("workday"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        assertThat(atsCompanyRepository.findById(companyId).orElseThrow().site()).isEqualTo("AcmeCareers");
        assertThat(workday.queriesSeen).hasSize(1);
        assertThat(workday.queriesSeen.get(0).site()).isEqualTo("AcmeCareers");
    }

    @Test
    void unresolvableWorkdaySiteIsTreatedAsDeadSlugAndTheRunContinues() {
        long companyId = addCompany("workday", "acme", "Acme", "acme.wd5.myworkdayjobs.com", null);
        when(workdaySiteResolver.resolve("acme.wd5.myworkdayjobs.com")).thenReturn(Optional.empty());

        FakeJobSource workday = new FakeJobSource("workday");
        AtsSweepService service = newService(workday);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "remote", 24, List.of("workday"), false),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        assertThat(atsCompanyRepository.findById(companyId).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(workday.queriesSeen).isEmpty(); // never fetched - failed before any request
    }

    /**
     * Collection is deliberately UNFILTERED by location now. Whether a posting is in the US is
     * decided after collection by {@code LocationClassifier} (one batched Claude call per run
     * instead of one per company), and non-US rows are then hidden by the {@code location_us}
     * predicate in the read queries. This asserts the sweep stores what the board returned and
     * does not try to judge locations itself.
     */
    @Test
    void collectionStoresEveryPostingAndLeavesLocationUnjudged() {
        addCompany("workday", "acme", "Acme", "acme.wd5.myworkdayjobs.com", "AcmeCareers");

        SourcedJob us = new SourcedJob("workday", "JR1", "Software Engineer", "Acme", "Austin, TX",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/1", null, "desc");
        SourcedJob israel = new SourcedJob("workday", "JR2", "Software Engineer, SONiC", "Acme", "Israel, Yokneam",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/2", null, "desc");
        SourcedJob india = new SourcedJob("workday", "JR3", "Software Engineer", "Acme", "India, Bangalore",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/3", null, "desc");

        FakeJobSource workday = new FakeJobSource("workday",
                new SourceFetchResult.Ok(List.of(us, israel, india), 1));
        AtsSweepService service = newService(workday);
        long runId = newRun();

        String status = service.run(runId, new AtsRunRequest("engineer", "", 24, List.of("workday"), true),
                NEVER_CANCELLED);

        assertThat(status).isEqualTo("ok");
        List<String> storedIds = client.sql("select source_job_id from job_listing where last_seen_run_id = :r")
                .param("r", runId)
                .query(String.class)
                .list();
        assertThat(storedIds).containsExactlyInAnyOrder("JR1", "JR2", "JR3");

        Integer unjudged = client.sql("""
                        select count(*) from job_listing
                        where last_seen_run_id = :r and location_us is null
                        """)
                .param("r", runId).query(Integer.class).single();
        assertThat(unjudged).as("the sweep must not decide location itself").isEqualTo(3);
    }

    @Test
    void usOnlyOffKeepsEverySourcedPosting() {
        addCompany("workday", "acme", "Acme", "acme.wd5.myworkdayjobs.com", "AcmeCareers");
        SourcedJob us = new SourcedJob("workday", "JR1", "Engineer", "Acme", "Austin, TX",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/1", null, "desc");
        SourcedJob abroad = new SourcedJob("workday", "JR2", "Engineer", "Acme", "Israel, Yokneam",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/2", null, "desc");

        FakeJobSource workday = new FakeJobSource("workday", new SourceFetchResult.Ok(List.of(us, abroad), 1));
        AtsSweepService service = newService(workday);
        long runId = newRun();

        service.run(runId, new AtsRunRequest("engineer", "", 24, List.of("workday"), false), NEVER_CANCELLED);

        assertThat(client.sql("select count(*) from job_listing where last_seen_run_id = :r")
                .param("r", runId).query(Integer.class).single()).isEqualTo(2);
    }

    /** A board that answers with only foreign roles is alive, not dead - liveness is not yield. */
    @Test
    void aCompanyWhosePostingsAreAllFilteredOutIsStillMarkedActive() {
        long companyId = addCompany("workday", "acme", "Acme", "acme.wd5.myworkdayjobs.com", "AcmeCareers");
        SourcedJob abroad = new SourcedJob("workday", "JR2", "Engineer", "Acme", "Israel, Yokneam",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/2", null, "desc");

        FakeJobSource workday = new FakeJobSource("workday", new SourceFetchResult.Ok(List.of(abroad), 1));
        AtsSweepService service = newService(workday);

        service.run(newRun(), new AtsRunRequest("engineer", "", 24, List.of("workday"), true), NEVER_CANCELLED);

        var company = atsCompanyRepository.findById(companyId).orElseThrow();
        assertThat(company.status()).isEqualTo("active");
        assertThat(company.consecutiveFailures()).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------

    private long countJobsForSource(String source) {
        return client.sql("select count(*) from job_listing where source = :source")
                .param("source", source)
                .query(Long.class)
                .single();
    }

    private static SourcedJob sourcedJob(String source, String company) {
        return new SourcedJob(source, "job-1", "Backend Engineer", company, "Remote",
                Instant.parse("2026-09-01T00:00:00Z"), "https://example.com/jobs/1", null, "Job description text.");
    }

    /** A no-op {@link SalaryEnrichmentService} - keeps the test offline, like SweepServiceTest's equivalent. */
    private static final class NoopEnrichment extends SalaryEnrichmentService {
        NoopEnrichment() {
            super(List.of(), null, null, null, null);
        }

        @Override
        public void enrichRun(long runId, String location, BooleanSupplier cancelled) {
            // no-op
        }
    }

    /** A fake {@link JobSource} that returns a scripted sequence of results, one per call. */
    private static final class FakeJobSource implements JobSource {
        private final String name;
        private final Deque<SourceFetchResult> results;
        private final List<SourceQuery> queriesSeen = new ArrayList<>();

        FakeJobSource(String name, SourceFetchResult... results) {
            this.name = name;
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SourceFetchResult fetch(SourceQuery q) {
            queriesSeen.add(q);
            if (results.isEmpty()) {
                throw new IllegalStateException("FakeJobSource(" + name + ") called more times than scripted");
            }
            return results.poll();
        }
    }

    /** Narrow row mapper so this test doesn't need to depend on JobListingRepository's package-private one. */
    private record JobListingRepositoryTestRowMapper(String description) {
        static JobListingRepositoryTestRowMapper map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new JobListingRepositoryTestRowMapper(rs.getString("description"));
        }
    }
}
