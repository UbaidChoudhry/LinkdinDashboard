package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.http.CircuitStateStore;
import com.ubaid.jobdash.store.ApplicantProfileRepository;
import com.ubaid.jobdash.store.ApplicationRepository;
import com.ubaid.jobdash.store.ApplyBatchRepository;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    @Autowired
    private ApplicantProfileRepository applicantProfileRepository;
    @Autowired
    private ApplyBatchRepository applyBatchRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ResumeRepository resumeRepository;

    @BeforeEach
    void resetData() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        client.sql("delete from job_listing").update();
        client.sql("delete from sweep_run").update();
        client.sql("delete from exclude_word where word not in ('Senior','Sr','Staff','Principal','Lead','Manager','Director','Intern')").update();
        client.sql("delete from company_blocklist").update();
        client.sql("delete from job_application").update();
        client.sql("delete from apply_batch").update();
        client.sql("delete from applicant_profile").update();
        client.sql("delete from resume").update();
        circuitStateStore.save(CircuitSnapshot.initial());
    }

    private long insertResume(boolean isDefault) {
        return resumeRepository.insert(new Resume(0, "Resume", "resume.pdf", "application/pdf",
                "data/resumes/x.pdf", "resume text", 11, isDefault, Instant.now()));
    }

    private void saveProfile() {
        applicantProfileRepository.save(new ApplicantProfile(
                "Jane Doe", "jane@example.com", "", "", "", "", "", false, "", "", Instant.now()));
    }

    // ---- test helpers -------------------------------------------------------------------

    private long createRunRow(Instant startedAt) {
        return sweepRunRepository.create(startedAt, "java", "remote", 24, false, null);
    }

    /**
     * Inserts a job card and returns the actual autoincrement {@code job_id} SQLite assigned to
     * it - {@code sourceJobId} is just a distinguishing label for the fixture, no longer the row's
     * primary key.
     */
    private long insertJob(long sourceJobId, long runId, String title, String company, Instant postedAt) {
        JobCardInsert card = new JobCardInsert("linkedin", String.valueOf(sourceJobId), title, company, "Remote",
                postedAt, "https://linkedin.com/jobs/view/" + sourceJobId,
                "https://linkedin.com/company/" + company, null);
        jobListingRepository.upsertAll(java.util.List.of(card), runId, Instant.now());
        return client.sql("select job_id from job_listing where source = 'linkedin' and source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
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
        long job1 = insertJob(1, run1, "Software Engineer", "Acme", t0);
        setVerdictPass(job1); // run1, no status -> should NOT appear in search (not current run)

        long job2 = insertJob(2, run1, "Backend Engineer", "Acme", t0);
        setVerdictPass(job2);
        setUserStatus(job2, "applied"); // run1, applied -> should appear in applied tab

        Instant t1 = Instant.parse("2026-08-27T00:00:00Z");
        long run2 = createRunRow(t1);
        long job3 = insertJob(3, run2, "Platform Engineer", "Acme", t1);
        setVerdictPass(job3); // run2 (current), no status -> SHOULD appear in search

        long job4 = insertJob(4, run2, "Data Engineer", "Acme", t1);
        setVerdictPass(job4);
        setUserStatus(job4, "applied"); // run2, applied -> should appear in applied tab too

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value((int) job3));

        mockMvc.perform(get("/api/jobs").param("tab", "applied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].jobId").value(
                        org.hamcrest.Matchers.containsInAnyOrder((int) job2, (int) job4)));
    }

    // ---- sort -------------------------------------------------------------------------------

    @Test
    void twoBucketSortPutsSalariedDescBeforeUnsalariedByRecency() throws Exception {
        long run = createRunRow(Instant.now());
        Instant old = Instant.parse("2026-08-01T00:00:00Z");
        Instant recent = Instant.parse("2026-08-27T00:00:00Z");

        long jobA = insertJob(11, run, "A", "Acme", old);
        setVerdictPass(jobA);
        setSalaryMax(jobA, 100000.0);

        long jobB = insertJob(12, run, "B", "Acme", recent);
        setVerdictPass(jobB);
        setSalaryMax(jobB, 150000.0);

        long jobC = insertJob(13, run, "C", "Acme", recent);
        setVerdictPass(jobC); // no salary

        long jobD = insertJob(14, run, "D", "Acme", old);
        setVerdictPass(jobD); // no salary

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].jobId").value((int) jobB))
                .andExpect(jsonPath("$[1].jobId").value((int) jobA))
                .andExpect(jsonPath("$[2].jobId").value((int) jobC))
                .andExpect(jsonPath("$[3].jobId").value((int) jobD));
    }

    @Test
    void jobsEndpointExposesSalarySourceDetail() throws Exception {
        long run = createRunRow(Instant.parse("2026-08-27T00:00:00Z"));
        long job = insertJob(50, run, "Software Engineer", "Amazon", Instant.parse("2026-08-27T00:00:00Z"));
        setVerdictPass(job);
        setSalary(job, 190000.0, "lca", "AMAZON.COM SERVICES LLC");

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value((int) job))
                .andExpect(jsonPath("$[0].salarySourceDetail").value("AMAZON.COM SERVICES LLC"));
    }

    // ---- job status round trip ---------------------------------------------------------------

    @Test
    void postJobStatusRoundTripsAndMovesBetweenTabs() throws Exception {
        long run = createRunRow(Instant.now());
        long job = insertJob(21, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/jobs/" + job + "/status")
                        .contentType("application/json")
                        .content("{\"status\":\"applied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userStatus").value("applied"));

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/jobs").param("tab", "applied"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/jobs/" + job + "/status")
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
        long job = insertJob(31, run, "Freelance Consultant", "Acme", Instant.now());
        setVerdictPass(job);

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

    // ---- resume + cooldown (the Retry behind a blocked LinkedIn run) -----------------------

    @Test
    void cooldownEndpointReportsTheOpenBreakerAndItsRemainingTime() throws Exception {
        mockMvc.perform(get("/api/runs/cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.remainingSeconds").value(0));

        Instant now = clock.instant();
        circuitStateStore.save(new CircuitSnapshot(CircuitState.OPEN, 1, 0, now, now.plusSeconds(600)));

        mockMvc.perform(get("/api/runs/cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.until").value(now.plusSeconds(600).toString()))
                // The context's Clock is the real one, so a second may tick between save and read.
                .andExpect(jsonPath("$.remainingSeconds", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(595), org.hamcrest.Matchers.lessThanOrEqualTo(600))));
    }

    @Test
    void resumeRunReturns404ForAnUnknownRun() throws Exception {
        mockMvc.perform(post("/api/runs/12345/resume"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resumeRunReturns409WhileTheRunItselfIsStillInFlight() throws Exception {
        long runId = createRunRow(Instant.now()); // status = running, finished_at = null

        mockMvc.perform(post("/api/runs/" + runId + "/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("nothing to resume yet")));
    }

    @Test
    void resumeRunReturns409WhileAnotherRunIsInFlight() throws Exception {
        long blocked = createRunRow(Instant.now().minusSeconds(600));
        sweepRunRepository.finish(blocked, Instant.now().minusSeconds(60), "blocked");
        long other = createRunRow(Instant.now());

        mockMvc.perform(post("/api/runs/" + blocked + "/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Run " + other + " is already in progress")));
    }

    /** The whole point of Retry is the cooldown - so while it is on, a resume is refused exactly like a new run. */
    @Test
    void resumeRunReturns503WhileTheLinkedInCooldownIsOn() throws Exception {
        long blocked = createRunRow(Instant.now().minusSeconds(600));
        sweepRunRepository.finish(blocked, Instant.now().minusSeconds(60), "blocked");
        Instant now = clock.instant();
        circuitStateStore.save(new CircuitSnapshot(CircuitState.OPEN, 1, 0, now, now.plusSeconds(600)));

        mockMvc.perform(post("/api/runs/" + blocked + "/resume"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("cooldown")));
        // Refused before anything was touched: the row is still finished and still "blocked".
        mockMvc.perform(get("/api/runs/" + blocked))
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
    }

    /** A finished run reports how many LinkedIn rows the detail phase never reached, so the UI can size Retry. */
    @Test
    void finishedRunReportsItsUnfetchedDescriptionCount() throws Exception {
        long runId = createRunRow(Instant.now().minusSeconds(600));
        long unread = insertJob(1L, runId, "Engineer", "Acme", Instant.now());
        long read = insertJob(2L, runId, "Engineer II", "Acme", Instant.now());
        long rejected = insertJob(3L, runId, "Senior Engineer", "Acme", Instant.now());
        setVerdictPass(unread);
        setVerdictPass(read);
        client.sql("update job_listing set filter_verdict = 'reject' where job_id = :id").param("id", rejected).update();
        client.sql("update job_listing set detail_fetched_at = :at, detail_status = 'ok', description = 'x' where job_id = :id")
                .param("at", Instant.now().toString()).param("id", read).update();

        // In flight: no count, the number is still moving.
        mockMvc.perform(get("/api/runs/" + runId))
                .andExpect(jsonPath("$.unfetchedDescriptions").doesNotExist());

        sweepRunRepository.finish(runId, Instant.now(), "blocked");
        mockMvc.perform(get("/api/runs/" + runId))
                .andExpect(jsonPath("$.unfetchedDescriptions").value(1));
    }

    @Test
    void createRunReturns400WhenKeywordsMissing() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"location\":\"USA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRunAcceptsLinkedInCombinedWithAnotherSource() throws Exception {
        // Mixed runs are valid since 2026-09-09. Proving validation passed without dispatching a
        // real run: with the breaker open, a request that clears validation gets the 503 cooldown
        // (the guard that runs AFTER validation), not a 400. No real HTTP call is made.
        Instant now = clock.instant();
        circuitStateStore.save(new CircuitSnapshot(CircuitState.OPEN, 1, 0, now, now.plusSeconds(600)));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"location\":\"USA\",\"sources\":[\"linkedin\",\"greenhouse\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("cooldown")));
    }

    @Test
    void createRunDefaultsSourcesToLinkedInWhenAbsent() throws Exception {
        // No "sources" field at all. The circuit-breaker guard only runs when "linkedin" is
        // among the selected sources (RunController#createRun), so getting the 503 cooldown
        // response here - rather than a run actually starting - proves the default resolved to
        // ["linkedin"], not an empty/no-op selection. Safe: the guard throws before any run
        // (LinkedIn or ATS) is ever dispatched, so no real HTTP call is made.
        Instant now = clock.instant();
        circuitStateStore.save(new CircuitSnapshot(CircuitState.OPEN, 1, 0, now, now.plusSeconds(600)));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"location\":\"USA\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("cooldown")));
    }

    @Test
    void createRunRequiresALocationForLinkedInButNotForAnAtsRun() throws Exception {
        // A LinkedIn run without a location is rejected: SweepQueryBuilder has to put the
        // location in the query string, so a blank one searches the wrong thing.
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"sources\":[\"linkedin\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("location is required")));

        // The SAME request against an ATS source must NOT be rejected: there, location is only a
        // local filter over what the board already returned, and blank legitimately means
        // "anywhere". Requiring one made a valid nationwide ATS run impossible to start.
        // Asserting "not a 400 complaining about location" rather than a specific success code,
        // because what happens next (a run starting) depends on the catalog, not on validation.
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"sources\":[\"greenhouse\"]}"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (result.getResponse().getStatus() == 400 && body.contains("location is required")) {
                        throw new AssertionError("An ATS run must not require a location, but got: " + body);
                    }
                });
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

    // ---- bulk status / delete (multi-select) -------------------------------------------------

    @Test
    void bulkStatusMovesEveryIdAndLeavesOthersUntouched() throws Exception {
        long run = createRunRow(Instant.now());
        long jobA = insertJob(31, run, "A", "Acme", Instant.now());
        long jobB = insertJob(32, run, "B", "Acme", Instant.now());
        long jobC = insertJob(33, run, "C", "Acme", Instant.now());
        setVerdictPass(jobA);
        setVerdictPass(jobB);
        setVerdictPass(jobC);

        mockMvc.perform(post("/api/jobs/bulk-status")
                        .contentType("application/json")
                        .content("{\"jobIds\":[" + jobA + "," + jobB + "],\"status\":\"not_interested\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(get("/api/jobs").param("tab", "not_interested"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].jobId").value(
                        org.hamcrest.Matchers.containsInAnyOrder((int) jobA, (int) jobB)));

        // jobC was never in the request, so it must still be untriaged.
        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value((int) jobC));
    }

    @Test
    void bulkStatusRejectsAnEmptyJobIdsList() throws Exception {
        mockMvc.perform(post("/api/jobs/bulk-status")
                        .contentType("application/json")
                        .content("{\"jobIds\":[],\"status\":\"not_interested\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void bulkDeleteRemovesOnlyTheGivenIds() throws Exception {
        long run = createRunRow(Instant.now());
        long jobA = insertJob(41, run, "A", "Acme", Instant.now());
        long jobB = insertJob(42, run, "B", "Acme", Instant.now());
        setVerdictPass(jobA);
        setVerdictPass(jobB);

        mockMvc.perform(post("/api/jobs/bulk-delete")
                        .contentType("application/json")
                        .content("{\"jobIds\":[" + jobA + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value((int) jobB));

        long remaining = client.sql("select count(*) from job_listing where job_id = :id")
                .param("id", jobA).query(Long.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
    }

    @Test
    void bulkDeleteRejectsAnEmptyJobIdsList() throws Exception {
        mockMvc.perform(post("/api/jobs/bulk-delete")
                        .contentType("application/json")
                        .content("{\"jobIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- applicant profile ------------------------------------------------------------------

    @Test
    void profileReturns204BeforeAnySaveThenRoundTripsOnPut() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/profile")
                        .contentType("application/json")
                        .content("""
                                {"fullName":"Jane Doe","email":"jane@example.com","phone":"555-1234",
                                 "location":"Remote","linkedinUrl":"https://linkedin.com/in/jane",
                                 "portfolioUrl":"https://jane.dev","workAuthorization":"US Citizen",
                                 "requiresSponsorship":false,"salaryExpectation":"150k",
                                 "extraAnswers":"n/a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.requiresSponsorship").value(false))
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.phone").value("555-1234"));
    }

    @Test
    void putProfileRejectsBlankFullName() throws Exception {
        mockMvc.perform(put("/api/profile")
                        .contentType("application/json")
                        .content("{\"fullName\":\"\",\"email\":\"jane@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- apply batches -----------------------------------------------------------------------

    @Test
    void startApplicationsReturns400WithoutAnApplicantProfile() throws Exception {
        long run = createRunRow(Instant.now());
        long job = insertJob(60, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);

        mockMvc.perform(post("/api/applications")
                        .contentType("application/json")
                        .content("{\"jobIds\":[" + job + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("applicant profile")));
    }

    @Test
    void startApplicationsReturns409WhenABatchIsAlreadyInFlight() throws Exception {
        saveProfile();
        long resumeId = insertResume(true);
        long run = createRunRow(Instant.now());
        long job = insertJob(61, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);

        applyBatchRepository.create(resumeId, false, 1, Instant.now()); // status = running

        mockMvc.perform(post("/api/applications")
                        .contentType("application/json")
                        .content("{\"jobIds\":[" + job + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- apply transcript log --------------------------------------------------------------

    @Test
    void jobLogEndpointServesTheTranscriptFileVerbatim() throws Exception {
        saveProfile();
        long resumeId = insertResume(true);
        long run = createRunRow(Instant.now());
        long job = insertJob(80, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);

        long batchId = applyBatchRepository.create(resumeId, false, 1, Instant.now());
        long applicationId = applicationRepository.create(batchId, job, "filling");

        Path transcript = tempDir.resolve("job-" + applicationId + ".log");
        java.nio.file.Files.writeString(transcript, "10:00:00 tool navigate https://acme.com\n");
        applicationRepository.setSession(applicationId, "session-uuid", transcript.toString());

        mockMvc.perform(get("/api/applications/" + batchId + "/jobs/" + applicationId + "/log"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("10:00:00 tool navigate https://acme.com\n"));
    }

    @Test
    void jobLogEndpointReturns404WhenNoTranscriptRecorded() throws Exception {
        saveProfile();
        long resumeId = insertResume(true);
        long run = createRunRow(Instant.now());
        long job = insertJob(81, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);

        long batchId = applyBatchRepository.create(resumeId, false, 1, Instant.now());
        long applicationId = applicationRepository.create(batchId, job, "queued");

        mockMvc.perform(get("/api/applications/" + batchId + "/jobs/" + applicationId + "/log"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- applicationStatus on GET /api/jobs ---------------------------------------------------

    @Test
    void jobsEndpointCarriesApplicationStatusFromLatestApplication() throws Exception {
        long run = createRunRow(Instant.now());
        long job = insertJob(70, run, "Engineer", "Acme", Instant.now());
        setVerdictPass(job);
        long resumeId = insertResume(true);

        long batchId = applyBatchRepository.create(resumeId, false, 1, Instant.now());
        long applicationId = applicationRepository.create(batchId, job, "queued");
        applicationRepository.update(applicationId, "needs_review", "review before submitting",
                0.42, Instant.now(), Instant.now());

        mockMvc.perform(get("/api/jobs").param("tab", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value((int) job))
                .andExpect(jsonPath("$[0].applicationStatus").value("needs_review"))
                .andExpect(jsonPath("$[0].applicationNotes").value("review before submitting"));
    }
}
