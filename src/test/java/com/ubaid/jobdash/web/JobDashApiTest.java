package com.ubaid.jobdash.web;

import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.http.CircuitStateStore;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the {@code com.ubaid.jobdash.web} REST API, against a real (but
 * temporary, per-class) SQLite database with genuine Flyway migrations — same spirit as
 * {@code AbstractStoreTest} in the store package, just driven through MockMvc instead of the
 * repositories directly. No real HTTP call to LinkedIn is ever made: {@code POST /api/runs}
 * success paths that would start {@link com.ubaid.jobdash.sweep.SweepService#startRun} are
 * deliberately not exercised here; only its validation/guard paths are.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JobDashApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("jobdash-web-test.db"));
    }

    private static final String[] SEEDED_WORDS = {
            "Senior", "Sr", "Staff", "Principal", "Lead", "Manager", "Director", "Intern"
    };

    @Autowired
    private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private JobListingRepository jobListingRepository;
    @Autowired
    private SweepRunRepository sweepRunRepository;
    @Autowired
    private CircuitStateStore circuitStateStore;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetData() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        client.sql("delete from job_listing").update();
        client.sql("delete from sweep_run").update();
        client.sql("delete from exclude_word where word not in ('Senior','Sr','Staff','Principal','Lead','Manager','Director','Intern')").update();
        client.sql("delete from company_blocklist").update();
        circuitStateStore.save(CircuitSnapshot.initial());
    }

    // ---- test helpers -------------------------------------------------------------------

    private long createRunRow(Instant startedAt) {
        return sweepRunRepository.create(startedAt, "java", "remote", 24, false, null);
    }

    private void insertJob(long jobId, long runId, String title, String company, Instant postedAt) {
        JobCardInsert card = new JobCardInsert(jobId, title, company, "Remote", postedAt,
                "https://linkedin.com/jobs/view/" + jobId, "https://linkedin.com/company/" + company);
        jobListingRepository.upsertAll(java.util.List.of(card), runId, Instant.now());
    }

    private void setVerdictPass(long jobId) {
        client.sql("update job_listing set filter_verdict = 'pass', filter_version = 1 where job_id = :id")
                .param("id", jobId).update();
    }

    private void setUserStatus(long jobId, String status) {
        client.sql("update job_listing set user_status = :status, user_status_at = :at where job_id = :id")
                .param("status", status).param("at", Instant.now().toString()).param("id", jobId).update();
    }

    private void setSalaryMax(long jobId, Double salaryMax) {
        client.sql("update job_listing set salary_max = :v where job_id = :id")
                .param("v", salaryMax).param("id", jobId).update();
    }

    private void setSalary(long jobId, Double salaryMax, String source, String sourceDetail) {
        client.sql("update job_listing set salary_max = :v, salary_source = :s, salary_source_detail = :d where job_id = :id")
                .param("v", salaryMax).param("s", source).param("d", sourceDetail).param("id", jobId).update();
    }

    // ---- tabs -----------------------------------------------------------------------------

    @Test
    void searchTabShowsOnlyCurrentRunPassingStatuslessRows_appliedShowsAcrossRuns() throws Exception {
        Instant t0 = Instant.parse("2026-08-20T00:00:00Z");
        long run1 = createRunRow(t0);
        insertJob(1, run1, "Software Engineer", "Acme", t0);
        setVerdictPass(1); // run1, no status -> should NOT appear in search (not current run)

        insertJob(2, run1, "Backend Engineer", "Acme", t0);
        setVerdictPass(2);
        setUserStatus(2, "applied"); // run1, applied -> should appear in applied tab

        Instant t1 = Instant.parse("2026-08-27T00:00:00Z");
        long run2 = createRunRow(t1);
        insertJob(3, run2, "Platform Engineer", "Acme", t1);
        setVerdictPass(3); // run2 (current), no status -> SHOULD appear in search

        insertJob(4, run2, "Data Engineer", "Acme", t1);
        setVerdictPass(4);
        setUserStatus(4, "applied"); // run2, applied -> should appear in applied tab too

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(3));

        mockMvc.perform(get("/api/jobs").param("tab", "applied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].jobId").value(org.hamcrest.Matchers.containsInAnyOrder(2, 4)));
    }

    // ---- sort -------------------------------------------------------------------------------

    @Test
    void twoBucketSortPutsSalariedDescBeforeUnsalariedByRecency() throws Exception {
        long run = createRunRow(Instant.now());
        Instant old = Instant.parse("2026-08-01T00:00:00Z");
        Instant recent = Instant.parse("2026-08-27T00:00:00Z");

        insertJob(11, run, "A", "Acme", old);
        setVerdictPass(11);
        setSalaryMax(11, 100000.0);

        insertJob(12, run, "B", "Acme", recent);
        setVerdictPass(12);
        setSalaryMax(12, 150000.0);

        insertJob(13, run, "C", "Acme", recent);
        setVerdictPass(13); // no salary

        insertJob(14, run, "D", "Acme", old);
        setVerdictPass(14); // no salary

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].jobId").value(12))
                .andExpect(jsonPath("$[1].jobId").value(11))
                .andExpect(jsonPath("$[2].jobId").value(13))
                .andExpect(jsonPath("$[3].jobId").value(14));
    }

    @Test
    void jobsEndpointExposesSalarySourceDetail() throws Exception {
        long run = createRunRow(Instant.parse("2026-08-27T00:00:00Z"));
        insertJob(50, run, "Software Engineer", "Amazon", Instant.parse("2026-08-27T00:00:00Z"));
        setVerdictPass(50);
        setSalary(50, 190000.0, "lca", "AMAZON.COM SERVICES LLC");

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(50))
                .andExpect(jsonPath("$[0].salarySourceDetail").value("AMAZON.COM SERVICES LLC"));
    }

    // ---- job status round trip ---------------------------------------------------------------

    @Test
    void postJobStatusRoundTripsAndMovesBetweenTabs() throws Exception {
        long run = createRunRow(Instant.now());
        insertJob(21, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(21);

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/jobs/21/status")
                        .contentType("application/json")
                        .content("{\"status\":\"applied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userStatus").value("applied"));

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/jobs").param("tab", "applied"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/jobs/21/status")
                        .contentType("application/json")
                        .content("{\"status\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userStatus").doesNotExist());

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/jobs").param("tab", "applied"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void postJobStatusOnUnknownJobReturns404() throws Exception {
        mockMvc.perform(post("/api/jobs/999999/status")
                        .contentType("application/json")
                        .content("{\"status\":\"applied\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- filter re-evaluation -----------------------------------------------------------------

    @Test
    void addingExcludeWordTriggersReevaluationAndDropsFromSearch() throws Exception {
        long run = createRunRow(Instant.now());
        insertJob(31, run, "Freelance Consultant", "Acme", Instant.now());
        setVerdictPass(31);

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/filters/words")
                        .contentType("application/json")
                        .content("{\"word\":\"Freelance\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(0));

        // clean up so this doesn't leak into other tests via the seeded-word assertions
        mockMvc.perform(delete("/api/filters/words/Freelance")).andExpect(status().isOk());
    }

    @Test
    void listWordsReturnsSeededWords() throws Exception {
        mockMvc.perform(get("/api/filters/words"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(SEEDED_WORDS.length));
    }

    // ---- run creation guards -------------------------------------------------------------------

    @Test
    void createRunReturns409WhenOneIsAlreadyInFlight() throws Exception {
        createRunRow(Instant.now()); // status = running, finished_at = null

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"location\":\"USA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already in progress")));
    }

    @Test
    void createRunReturns503WhenCircuitOpen() throws Exception {
        Instant now = clock.instant();
        circuitStateStore.save(new CircuitSnapshot(CircuitState.OPEN, 1, 0, now, now.plusSeconds(600)));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"location\":\"USA\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("cooldown")));
    }

    @Test
    void createRunReturns400WhenKeywordsMissing() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"location\":\"USA\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- reports ---------------------------------------------------------------------------

    @Test
    void companyVolumeReportGroupsAndThresholds() throws Exception {
        long run = createRunRow(Instant.now());
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);

        for (int i = 0; i < 5; i++) {
            insertJob(100 + i, run, "Title " + i, "BigCo", recent);
        }
        insertJob(200, run, "Only Role", "SmallCo", recent);
        insertJob(201, run, "Only Role 2", "SmallCo", recent);

        mockMvc.perform(get("/api/reports/company-volume").param("days", "7").param("threshold", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].company").value("BigCo"))
                .andExpect(jsonPath("$[0].postings").value(5));
    }

    @Test
    void jobsEndpointRejectsUnknownTab() throws Exception {
        mockMvc.perform(get("/api/jobs").param("tab", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
