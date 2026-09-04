package com.ubaid.jobdash.web;

import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.RequestLogRepository;
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
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the Data tab endpoints, same real-temp-SQLite-plus-Flyway spirit as
 * {@link JobDashApiTest}. Kept as its own class since it exercises a genuinely on-disk database
 * (never {@code :memory:}) - the whole point of {@code /api/data/stats} is reporting real file
 * size, so faking the datasource here would test nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DataControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("jobdash-data-test.db"));
    }

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
    private RequestLogRepository requestLogRepository;

    @BeforeEach
    void resetData() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        client.sql("delete from job_listing").update();
        client.sql("delete from sweep_run").update();
        client.sql("delete from request_log").update();
        client.sql("delete from company_blocklist").update();
    }

    private long createFinishedRun() {
        long id = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        sweepRunRepository.finish(id, Instant.now(), "ok");
        return id;
    }

    private void insertJob(long sourceJobId, long runId, String verdict, String userStatus) {
        JobCardInsert card = new JobCardInsert("linkedin", String.valueOf(sourceJobId), "Engineer " + sourceJobId,
                "Acme", "Remote", Instant.now(), "https://linkedin.com/jobs/view/" + sourceJobId, null, null);
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        // job_id is now an internal autoincrement surrogate, decoupled from sourceJobId - target
        // these fixture updates by the natural key instead.
        if (verdict != null) {
            client.sql("update job_listing set filter_verdict = :v, filter_version = 1 where source_job_id = :id")
                    .param("v", verdict).param("id", String.valueOf(sourceJobId)).update();
        }
        if (userStatus != null) {
            client.sql("update job_listing set user_status = :s, user_status_at = :at where source_job_id = :id")
                    .param("s", userStatus).param("at", Instant.now().toString())
                    .param("id", String.valueOf(sourceJobId)).update();
        }
    }

    @Test
    void statsOnAFreshDatabaseAreAllZeroNotAnError() throws Exception {
        mockMvc.perform(get("/api/data/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(0))
                .andExpect(jsonPath("$.totalRuns").value(0))
                .andExpect(jsonPath("$.oldestFirstSeenAt").doesNotExist())
                .andExpect(jsonPath("$.databaseSizeBytes").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void statsReflectSeededJobsRunsAndRequestLog() throws Exception {
        long run = createFinishedRun();
        insertJob(1, run, "pass", null);
        insertJob(2, run, "pass", "applied");
        insertJob(3, run, "reject", null);
        requestLogRepository.append(run, Instant.now(), "https://x", 200, "ok", 100, 8000);

        mockMvc.perform(get("/api/data/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(3))
                .andExpect(jsonPath("$.jobsPassed").value(2))
                .andExpect(jsonPath("$.jobsRejected").value(1))
                .andExpect(jsonPath("$.jobsApplied").value(1))
                .andExpect(jsonPath("$.jobsUntriaged").value(2))
                .andExpect(jsonPath("$.totalRuns").value(1))
                .andExpect(jsonPath("$.requestLogEntries").value(1))
                .andExpect(jsonPath("$.requestsLast24h").value(1))
                .andExpect(jsonPath("$.excludeWordCount").value(8));
    }

    @Test
    void clearRemovesJobsAndRunsButNotRequestLogOrFilterLists() throws Exception {
        long run = createFinishedRun();
        insertJob(1, run, "pass", null);
        insertJob(2, run, "pass", "applied");
        requestLogRepository.append(run, Instant.now(), "https://x", 200, "ok", 100, 8000);

        mockMvc.perform(post("/api/data/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobsCleared").value(2))
                .andExpect(jsonPath("$.runsCleared").value(1));

        mockMvc.perform(get("/api/data/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(0))
                .andExpect(jsonPath("$.totalRuns").value(0))
                // The rate limiter's audit trail must survive a clear - otherwise "clear the DB"
                // becomes a way to silently reset the daily request budget.
                .andExpect(jsonPath("$.requestLogEntries").value(1))
                .andExpect(jsonPath("$.excludeWordCount").value(8));

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clearRefusesWithConflictWhileARunIsInFlight() throws Exception {
        sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null); // never finished

        mockMvc.perform(post("/api/data/clear"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void clearOnAnEmptyDatabaseSucceedsWithZeroCounts() throws Exception {
        mockMvc.perform(post("/api/data/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobsCleared").value(0))
                .andExpect(jsonPath("$.runsCleared").value(0));
    }
}
