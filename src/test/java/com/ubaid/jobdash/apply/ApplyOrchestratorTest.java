package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ApplyOrchestrator} against a real (temp-file) database via
 * {@link AbstractStoreTest}, with a fake {@link ClaudeCliClient} standing in for the real
 * binary - no test here may invoke the real CLI or touch the network or the browser.
 */
class ApplyOrchestratorTest extends AbstractStoreTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private ResumeRepository resumeRepository;
    private AtsCompanyRepository atsCompanyRepository;

    @BeforeEach
    void setUpAdditional() {
        resumeRepository = new ResumeRepository(client);
        atsCompanyRepository = new AtsCompanyRepository(client);
    }

    private ApplyProperties props() {
        return new ApplyProperties(true, "sonnet", Duration.ofSeconds(10), 60, 2.0, 4000,
                Duration.ofMinutes(3), tempDir.resolve("apply-logs").toString(), "medium", 1);
    }

    private ApplyOrchestrator orchestrator(FakeCliClient cli) {
        return new ApplyOrchestrator(cli, props(), new ApplyPromptBuilder(),
                new ApplyUrlResolver(atsCompanyRepository), applyBatchRepository,
                applicationRepository, jobListingRepository, resumeRepository, applicantProfileRepository,
                profileAnswerRepository, new FakeFormQuestionPrefetcher(), clock);
    }

    private long insertResume() {
        return resumeRepository.insert(new Resume(0, "r1", "resume.pdf", "application/pdf", "resumes/1.pdf",
                "Experienced backend engineer.", 30, false, Instant.now()));
    }

    private void saveProfile() {
        applicantProfileRepository.save(new ApplicantProfile("Ada Lovelace", "ada@example.com", "555-1000",
                "New York, NY", "https://linkedin.com/in/ada", "https://ada.dev", "US Citizen", false,
                "$150,000", "", Instant.now()));
    }

    private long newRun() {
        return sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
    }

    private long insertJob(long runId, long sourceJobId, String source) {
        JobCardInsert card = new JobCardInsert(source, String.valueOf(sourceJobId), "Backend Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-08-27T00:00:00Z"),
                "https://acme.com/jobs/" + sourceJobId, "https://acme.com", "Great role");
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        long jobId = client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
        jobListingRepository.applyVerdicts(
                List.of(new JobListingRepository.VerdictUpdate(jobId, FilterVerdict.PASS, null)), 1);
        return jobId;
    }

    /**
     * Sets {@code apply_url}/{@code apply_domain} directly via {@link org.springframework.jdbc.core.simple.JdbcClient}
     * rather than a repository method, since the LinkedIn-ATS-matching phase that normally populates
     * these columns is being added concurrently by another agent.
     */
    private void setApplyMatch(long jobId, String applyDomain, String applyUrl) {
        client.sql("update job_listing set apply_domain = :domain, apply_url = :url where job_id = :id")
                .param("domain", applyDomain)
                .param("url", applyUrl)
                .param("id", jobId)
                .update();
    }

    /**
     * A fake CLI client whose 4-arg runStreaming response is computed by the given function; before
     * returning it, it emits two canned tool events to the listener - the second's text is what a
     * test can expect to land in {@code last_activity}.
     */
    private static final class FakeCliClient extends ClaudeCliClient {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();
        private final List<List<String>> argsPerCall = new ArrayList<>();
        private final Function<String, CliJsonResult> responder;

        FakeCliClient(Function<String, CliJsonResult> responder) {
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, false, 200, 40),
                    JsonMapper.builder().build());
            this.responder = responder;
        }

        @Override
        public CliJsonResult runStreaming(String prompt, String jsonSchema, StreamOptions options,
                                           Consumer<CliEvent> listener) {
            invocations.incrementAndGet();
            synchronized (prompts) {
                prompts.add(prompt);
                argsPerCall.add(options.args());
            }
            listener.accept(new CliEvent("tool", "navigate https://acme.com"));
            listener.accept(new CliEvent("tool", "click Apply button"));
            return responder.apply(prompt);
        }

        int invocationCount() {
            return invocations.get();
        }

        List<String> prompts() {
            synchronized (prompts) {
                return List.copyOf(prompts);
            }
        }

        List<List<String>> argsPerCall() {
            synchronized (prompts) {
                return List.copyOf(argsPerCall);
            }
        }
    }

    /** Never touches the network: returns a fixed canned form-questions list for every URL. */
    private static final class FakeFormQuestionPrefetcher extends FormQuestionPrefetcher {
        @Override
        public List<String> prefetch(String directFormUrl) {
            return List.of("Pre-read question one", "Pre-read question two");
        }
    }

    private static CliJsonResult ok(String outcome, String summary) {
        return new CliJsonResult.Ok(
                JsonMapper.builder().build().readTree(
                        "{\"outcome\":\"" + outcome + "\",\"summary\":\"" + summary + "\"}"),
                0.10, 1000);
    }

    private static CliJsonResult okWithUnanswered(String outcome, String summary, List<String> unanswered) {
        String quoted = unanswered.stream().map(q -> "\"" + q + "\"").collect(java.util.stream.Collectors.joining(","));
        return new CliJsonResult.Ok(
                JsonMapper.builder().build().readTree(
                        "{\"outcome\":\"" + outcome + "\",\"summary\":\"" + summary + "\",\"unanswered\":[" + quoted
                                + "]}"),
                0.10, 1000);
    }

    @Test
    void linkedInJobIsSkippedWithZeroCliCallsAndTheAtsJobIsProcessed() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long linkedinJob = insertJob(runId, 1L, "linkedin");
        long atsJob = insertJob(runId, 2L, "lever");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "filled the form"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(linkedinJob, atsJob), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.invocationCount()).isEqualTo(1);

        List<JobApplication> apps = applicationRepository.findByBatch(batchId);
        JobApplication linkedinApp = apps.stream().filter(a -> a.jobId() == linkedinJob).findFirst().orElseThrow();
        JobApplication atsApp = apps.stream().filter(a -> a.jobId() == atsJob).findFirst().orElseThrow();

        assertThat(linkedinApp.status()).isEqualTo("skipped");
        assertThat(linkedinApp.notes()).isEqualTo("LinkedIn: no company-site match - apply manually");
        assertThat(atsApp.status()).isEqualTo("needs_review");

        assertThat(applyBatchRepository.findById(batchId).orElseThrow().status()).isEqualTo("ok");
    }

    @Test
    void submittedOutcomeSetsUserStatusApplied() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 3L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("submitted", "application submitted"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, true);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("submitted");

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.userStatus()).isEqualTo(UserStatus.APPLIED);
    }

    @Test
    void needsReviewOutcomeLeavesUserStatusNullAndStoresNotes() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 4L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("needs_review");
        assertThat(app.notes()).contains("stopped before submit");

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.userStatus()).isNull();
    }

    @Test
    void failedCliResultMarksJobFailedAndBatchStillFinishesOk() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 5L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> new CliJsonResult.Failed("login wall", -1));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("failed");
        assertThat(app.notes()).isEqualTo("login wall");

        assertThat(applyBatchRepository.findById(batchId).orElseThrow().status()).isEqualTo("ok");
    }

    @Test
    void cancelBeforeSecondJobLeavesItQueuedAndEndsBatchCancelled() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long firstJob = insertJob(runId, 6L, "greenhouse");
        long secondJob = insertJob(runId, 7L, "greenhouse");

        ApplyOrchestrator[] holder = new ApplyOrchestrator[1];
        FakeCliClient cli = new FakeCliClient(prompt -> {
            // Cancel as soon as the first job's CLI call happens, before the second job is reached.
            holder[0].cancel(holder[0].inFlightBatchId().orElseThrow());
            return ok("needs_review", "filled the form");
        });
        ApplyOrchestrator orchestrator = orchestrator(cli);
        holder[0] = orchestrator;

        long batchId = orchestrator.start(List.of(firstJob, secondJob), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.invocationCount()).isEqualTo(1);

        List<JobApplication> apps = applicationRepository.findByBatch(batchId);
        JobApplication first = apps.stream().filter(a -> a.jobId() == firstJob).findFirst().orElseThrow();
        JobApplication second = apps.stream().filter(a -> a.jobId() == secondJob).findFirst().orElseThrow();

        assertThat(first.status()).isEqualTo("needs_review");
        assertThat(second.status()).isEqualTo("queued");

        assertThat(applyBatchRepository.findById(batchId).orElseThrow().status()).isEqualTo("cancelled");
    }

    @Test
    void submitFalsePromptContainsNeedsReview() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 8L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("needs_review");
    }

    @Test
    void chromeArgsContainChromeFlag() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 9L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.argsPerCall()).hasSize(1);
        assertThat(cli.argsPerCall().get(0)).contains("--chrome");
    }

    @Test
    void argsUseStreamJsonWithSessionIdAndNoSessionPersistenceRemoved() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 10L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        List<String> args = cli.argsPerCall().get(0);
        assertThat(args).contains("--session-id");
        assertThat(args).contains("stream-json");
        assertThat(args).doesNotContain("--no-session-persistence");
    }

    @Test
    void sessionIdLogPathAndLastActivityAreRecordedAndTranscriptFileIsWritten() throws InterruptedException, IOException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 11L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.sessionId()).isNotBlank();
        assertThat(app.logPath()).isNotBlank();
        assertThat(app.lastActivity()).isEqualTo("click Apply button");

        Path transcript = Path.of(app.logPath());
        assertThat(Files.exists(transcript)).isTrue();
        List<String> lines = Files.readAllLines(transcript);
        assertThat(lines).anyMatch(l -> l.contains("tool") && l.contains("navigate https://acme.com"));
        assertThat(lines).anyMatch(l -> l.contains("tool") && l.contains("click Apply button"));
    }

    @Test
    void greenhouseJobPromptContainsDirectApplyFormUrlResolvedFromCatalog() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        atsCompanyRepository.insertOne("greenhouse", "acme", "Acme Corp", null, null, true, "active",
                Instant.now());
        long runId = newRun();
        long jobId = insertJob(runId, 13L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("DIRECT APPLY FORM URL: https://job-boards.greenhouse.io/embed/job_app?for=");
    }

    @Test
    void noActivityFailureProducesStuckNotesWithResumeCommand() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 12L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(
                prompt -> new CliJsonResult.Failed("no activity from claude for 180s - killed", -1));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("failed");
        assertThat(app.notes()).startsWith("Stuck: no activity from claude for 180s - killed.");
        assertThat(app.notes()).contains("claude --resume " + app.sessionId() + " --chrome");
    }

    @Test
    void errorMaxTurnsFailureProducesResumeNoteAndCostFlowsToJobAndBatch() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 14L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(
                prompt -> new CliJsonResult.Failed("claude stopped early: error_max_turns", -1, 1.25));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("failed");
        assertThat(app.notes()).contains(
                "Ran out of browser actions (apply.max-turns = " + props().maxTurns() + ")");
        assertThat(app.notes()).contains("mostly filled");
        assertThat(app.notes()).contains("claude --resume " + app.sessionId() + " --chrome");
        assertThat(app.costUsd()).isEqualTo(1.25);

        assertThat(applyBatchRepository.findById(batchId).orElseThrow().costUsd()).isEqualTo(1.25);
    }

    @Test
    void errorMaxBudgetFailureProducesResumeNoteAndCostFlowsToJobAndBatch() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 15L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(
                prompt -> new CliJsonResult.Failed("claude stopped early: error_max_budget", -1, 1.99));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("failed");
        assertThat(app.notes()).contains(
                "Hit the per-job cost cap (apply.max-budget-usd = " + props().maxBudgetUsd() + ")");
        assertThat(app.notes()).contains("mostly filled");
        assertThat(app.notes()).contains("claude --resume " + app.sessionId() + " --chrome");
        assertThat(app.costUsd()).isEqualTo(1.99);

        assertThat(applyBatchRepository.findById(batchId).orElseThrow().costUsd()).isEqualTo(1.99);
    }

    @Test
    void keepAwakeIsStartedBeforeFirstJobAndStoppedAfterBatchEvenWhenAJobThrows() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long firstJob = insertJob(runId, 16L, "greenhouse");
        long secondJob = insertJob(runId, 17L, "greenhouse");

        AtomicInteger startCount = new AtomicInteger();
        AtomicInteger stopCount = new AtomicInteger();
        List<String> events = new ArrayList<>();
        ApplyOrchestrator.KeepAwakeStarter fakeStarter = () -> {
            startCount.incrementAndGet();
            events.add("start");
            return () -> {
                stopCount.incrementAndGet();
                events.add("stop");
            };
        };

        AtomicInteger callCount = new AtomicInteger();
        FakeCliClient cli = new FakeCliClient(prompt -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
            return ok("needs_review", "filled the form");
        });
        ApplyOrchestrator orchestrator = new ApplyOrchestrator(cli, props(), new ApplyPromptBuilder(),
                new ApplyUrlResolver(atsCompanyRepository), applyBatchRepository,
                applicationRepository, jobListingRepository, resumeRepository, applicantProfileRepository,
                profileAnswerRepository, new FakeFormQuestionPrefetcher(), clock, fakeStarter);

        long batchId = orchestrator.start(List.of(firstJob, secondJob), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(startCount.get()).isEqualTo(1);
        assertThat(stopCount.get()).isEqualTo(1);
        assertThat(events.get(0)).isEqualTo("start");
        assertThat(events.get(events.size() - 1)).isEqualTo("stop");

        List<JobApplication> apps = applicationRepository.findByBatch(batchId);
        assertThat(apps).extracting(JobApplication::status).contains("failed");
    }

    @Test
    void unansweredQuestionsAreRecordedRepeatsAreBumpedAndReportIsWritten() throws InterruptedException, IOException {
        long resumeId = insertResume();
        saveProfile();
        profileAnswerRepository.insert("Portfolio link?", "https://ada.dev", "answered", null, "", Instant.now());
        long runId = newRun();
        long firstJob = insertJob(runId, 20L, "greenhouse");
        long secondJob = insertJob(runId, 21L, "greenhouse");

        AtomicInteger callCount = new AtomicInteger();
        FakeCliClient cli = new FakeCliClient(prompt -> {
            if (callCount.incrementAndGet() == 1) {
                return okWithUnanswered("needs_review", "filled most of it",
                        List.of("Do you require sponsorship?", "What is your desired start date?"));
            }
            return okWithUnanswered("needs_review", "filled most of it",
                    List.of("Do you require sponsorship?"));
        });
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(firstJob, secondJob), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.prompts().get(0)).contains("https://ada.dev");

        assertThat(profileAnswerRepository.findByKey("do you require sponsorship").orElseThrow().askedCount())
                .isEqualTo(1);
        assertThat(profileAnswerRepository.list().stream().filter(a -> "pending".equals(a.status())).count())
                .isEqualTo(2);

        com.ubaid.jobdash.domain.ApplyBatch batch = applyBatchRepository.findById(batchId).orElseThrow();
        assertThat(batch.newQuestions()).isEqualTo(2);
        assertThat(batch.reportPath()).isNotBlank();

        Path reportPath = Path.of(batch.reportPath());
        assertThat(Files.exists(reportPath)).isTrue();
        String report = Files.readString(reportPath);
        assertThat(report).contains("Do you require sponsorship?");
        assertThat(report).contains("What is your desired start date?");
        assertThat(report).contains("claude --resume");
    }

    @Test
    void matchedLinkedinGreenhouseRowReachesCliOnceWithApplyUrlAsJobUrlAndLogsTranscriptLine()
            throws InterruptedException, IOException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 30L, "linkedin");
        String applyUrl = "https://job-boards.greenhouse.io/embed/job_app?for=acme&token=999";
        setApplyMatch(jobId, "greenhouse", applyUrl);

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("JOB URL: " + applyUrl);

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        Path transcript = Path.of(app.logPath());
        List<String> lines = Files.readAllLines(transcript);
        assertThat(lines).anyMatch(l -> l.contains("linkedin → greenhouse"));
    }

    @Test
    void matchedLinkedinWorkdayRowReachesCliWithWorkdayPostingUrlAndNoDirectApplyFormUrl()
            throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 31L, "linkedin");
        String workdayUrl = "https://acme.wd1.myworkdayjobs.com/en-US/Careers/job/Remote/Backend-Engineer_R-999";
        setApplyMatch(jobId, "workday", workdayUrl);

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("JOB URL: " + workdayUrl);
        assertThat(cli.prompts().get(0)).doesNotContain("DIRECT APPLY FORM URL:");
    }

    @Test
    void unmatchedLinkedinRowIsSkippedWithZeroCliCallsAndTheNewNoteText() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 32L, "linkedin");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "stopped before submit"));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(jobId), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(cli.invocationCount()).isEqualTo(0);

        JobApplication app = applicationRepository.findByBatch(batchId).get(0);
        assertThat(app.status()).isEqualTo("skipped");
        assertThat(app.notes()).isEqualTo("LinkedIn: no company-site match - apply manually");
    }

    @Test
    void reapOrphanedBatchesClosesADeadRunningBatchButLeavesTheLiveOneAlone() {
        long resumeId = insertResume();
        long runId = newRun();
        long job1 = insertJob(runId, 71L, "greenhouse");
        long job2 = insertJob(runId, 72L, "greenhouse");

        // A batch left 'running' by a process that died: one job mid-fill, one still queued.
        long dead = applyBatchRepository.create(resumeId, false, 2, clock.instant());
        long fillingApp = applicationRepository.create(dead, job1, "queued");
        applicationRepository.update(fillingApp, "filling", "", 0.25, clock.instant(), null);
        long queuedApp = applicationRepository.create(dead, job2, "queued");
        long finished = applyBatchRepository.create(resumeId, false, 0, clock.instant());
        applyBatchRepository.finish(finished, "ok", clock.instant());

        ApplyOrchestrator orchestrator = orchestrator(new FakeCliClient(prompt -> ok("needs_review", "x")));
        int reaped = orchestrator.reapOrphanedBatches(ApplyOrchestrator.INTERRUPTED, "interrupted: restarted");

        assertThat(reaped).isEqualTo(1);
        assertThat(applyBatchRepository.findById(dead).orElseThrow().status()).isEqualTo("interrupted");
        assertThat(applyBatchRepository.findById(dead).orElseThrow().finishedAt()).isNotNull();
        assertThat(applyBatchRepository.findById(finished).orElseThrow().status()).isEqualTo("ok");
        assertThat(applyBatchRepository.findInFlight()).isEmpty();
        List<JobApplication> apps = applicationRepository.findByBatch(dead);
        assertThat(apps).allSatisfy(a -> {
            assertThat(a.status()).isEqualTo("failed");
            assertThat(a.notes()).isEqualTo("interrupted: restarted");
            assertThat(a.finishedAt()).isNotNull();
        });
        assertThat(apps.stream().filter(a -> a.id() == fillingApp).findFirst().orElseThrow().costUsd())
                .as("cost already spent on the interrupted job is kept").isEqualTo(0.25);
        assertThat(apps.stream().filter(a -> a.id() == queuedApp).findFirst().orElseThrow().startedAt()).isNotNull();

        // Nothing running: a second reap is a no-op.
        assertThat(orchestrator.reapOrphanedBatches(ApplyOrchestrator.INTERRUPTED, "again")).isZero();
    }

    @Test
    void reapOrphanedBatchesSkipsTheBatchThisProcessIsRunning() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 73L, "greenhouse");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FakeCliClient cli = new FakeCliClient(prompt -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("needs_review", "filled");
        });
        ApplyOrchestrator orchestrator = orchestrator(cli);
        long live = orchestrator.start(List.of(jobId), resumeId, false);

        try {
            assertThat(orchestrator.reapOrphanedBatches(ApplyOrchestrator.INTERRUPTED, "restarted")).isZero();
            assertThat(applyBatchRepository.findById(live).orElseThrow().status()).isEqualTo("running");
        } finally {
            release.countDown();
            orchestrator.awaitLastBatch();
        }
        assertThat(applyBatchRepository.findById(live).orElseThrow().status()).isEqualTo("ok");
    }

    @Test
    void resumeIsAttachedUnderItsOriginalUploadNameNotTheIdNamedStoredFile() throws IOException, InterruptedException {
        Path stored = tempDir.resolve("resumes").resolve("3.pdf");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[] {37, 80, 68, 70});
        long resumeId = resumeRepository.insert(new Resume(0, "r1", "Choudhry_Resume V19.pdf", "application/pdf",
                stored.toString(), "Experienced backend engineer.", 30, false, Instant.now()));
        saveProfile();
        long runId = newRun();
        long jobId = insertJob(runId, 74L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> ok("needs_review", "filled"));
        orchestrator(cli).start(List.of(jobId), resumeId, false);
        orchestrator(cli).awaitLastBatch();
        Thread.sleep(50);

        Path expected = stored.getParent().resolve("named").resolve(String.valueOf(resumeId))
                .resolve("Choudhry_Resume V19.pdf");
        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("RESUME FILE PATH: " + expected.toAbsolutePath());
        assertThat(cli.prompts().get(0)).doesNotContain("RESUME FILE PATH: " + stored.toAbsolutePath());
        assertThat(Files.readAllBytes(expected)).containsExactly(37, 80, 68, 70);
    }

    // ---- parallel batches (2026-09-23) ------------------------------------------------------------

    @Test
    void jobsRunSideBySideUpToTheRequestedConcurrency() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        List<Long> jobIds = List.of(insertJob(runId, 80L, "greenhouse"), insertJob(runId, 81L, "greenhouse"),
                insertJob(runId, 82L, "greenhouse"));

        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        FakeCliClient cli = new FakeCliClient(prompt -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            twoStarted.countDown();
            awaitQuietly(twoStarted);
            running.decrementAndGet();
            return ok("needs_review", "filled");
        });
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(jobIds, resumeId, false, 2);
        orchestrator.awaitLastBatch();

        // The first two calls only return once both are in flight, so a sequential batch would
        // time out the latch and still report 1 here.
        assertThat(maxRunning.get()).isEqualTo(2);
        com.ubaid.jobdash.domain.ApplyBatch batch = applyBatchRepository.findById(batchId).orElseThrow();
        assertThat(batch.status()).isEqualTo("ok");
        assertThat(batch.done()).isEqualTo(3);
        assertThat(batch.needsReview()).isEqualTo(3);
        assertThat(applicationRepository.findByBatch(batchId)).extracting(JobApplication::status)
                .containsOnly("needs_review");
    }

    @Test
    void twoJobsOnOneWorkdayTenantNeverOverlapButOtherJobsRunBesideThem() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        long firstWorkday = insertJob(runId, 90L, "linkedin");
        setApplyMatch(firstWorkday, "workday", "https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/A_R-90");
        long secondWorkday = insertJob(runId, 91L, "linkedin");
        setApplyMatch(secondWorkday, "workday", "https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/B_R-91");
        long greenhouse = insertJob(runId, 92L, "greenhouse");

        AtomicInteger workdayRunning = new AtomicInteger();
        AtomicInteger maxWorkdayRunning = new AtomicInteger();
        CountDownLatch greenhouseStarted = new CountDownLatch(1);
        AtomicInteger workdayCalls = new AtomicInteger();
        FakeCliClient cli = new FakeCliClient(prompt -> {
            if (!prompt.contains("acme.wd5.myworkdayjobs.com")) {
                greenhouseStarted.countDown();
                return ok("needs_review", "filled");
            }
            maxWorkdayRunning.accumulateAndGet(workdayRunning.incrementAndGet(), Math::max);
            if (workdayCalls.incrementAndGet() == 1) {
                // Holds the tenant until the Greenhouse job has started: proves it did not wait.
                awaitQuietly(greenhouseStarted);
            }
            workdayRunning.decrementAndGet();
            return ok("needs_review", "filled");
        });
        ApplyOrchestrator orchestrator = orchestrator(cli);

        long batchId = orchestrator.start(List.of(firstWorkday, secondWorkday, greenhouse), resumeId, false, 3);
        orchestrator.awaitLastBatch();

        assertThat(greenhouseStarted.getCount()).isZero();
        assertThat(maxWorkdayRunning.get()).isEqualTo(1);
        assertThat(cli.invocationCount()).isEqualTo(3);
        assertThat(applyBatchRepository.findById(batchId).orElseThrow().needsReview()).isEqualTo(3);
    }

    @Test
    void workdayJobsAreKeyedByTenantHostAndEverythingElseRunsFree() {
        JobListing sameTenant = jobListingRow("workday", "https://dowjones.wd1.myworkdayjobs.com/en-US/X/job/Y", null);
        JobListing viaLinkedin = jobListingRow("linkedin", "https://www.linkedin.com/jobs/view/1",
                "https://DowJones.wd1.myworkdayjobs.com/en-US/X/job/Z/apply");
        JobListing greenhouseRow = jobListingRow("greenhouse", "https://job-boards.greenhouse.io/acme/jobs/1", null);

        assertThat(ApplyOrchestrator.exclusiveKey(sameTenant)).isEqualTo("dowjones.wd1.myworkdayjobs.com");
        assertThat(ApplyOrchestrator.exclusiveKey(viaLinkedin)).isEqualTo("dowjones.wd1.myworkdayjobs.com");
        assertThat(ApplyOrchestrator.exclusiveKey(greenhouseRow)).isNull();
        assertThat(ApplyOrchestrator.exclusiveKey(null)).isNull();
    }

    @Test
    void pastedRowTakesTitleAndCompanyFromClaudeAndOtherRowsKeepTheirs() throws InterruptedException {
        long resumeId = insertResume();
        saveProfile();
        long runId = newRun();
        String url = "https://jobs.lever.co/acme/0f8ae1f2-2a5c-4a5e-9d4b-1c2d3e4f5a6b";
        long pasted = new PastedUrlJobs(jobListingRepository).importUrls(List.of(url), Instant.now()).get(0);
        long greenhouse = insertJob(runId, 95L, "greenhouse");

        FakeCliClient cli = new FakeCliClient(prompt -> new CliJsonResult.Ok(JsonMapper.builder().build().readTree(
                "{\"outcome\":\"needs_review\",\"summary\":\"filled\",\"jobTitle\":\"Platform Engineer\","
                        + "\"company\":\"Acme Robotics\"}"), 0.10, 1000));
        ApplyOrchestrator orchestrator = orchestrator(cli);

        orchestrator.start(List.of(pasted, greenhouse), resumeId, false, 2);
        orchestrator.awaitLastBatch();

        JobListing pastedRow = jobListingRepository.findById(pasted).orElseThrow();
        assertThat(pastedRow.title()).isEqualTo("Platform Engineer");
        assertThat(pastedRow.company()).isEqualTo("Acme Robotics");
        JobListing greenhouseRow = jobListingRepository.findById(greenhouse).orElseThrow();
        assertThat(greenhouseRow.title()).isEqualTo("Backend Engineer");
        assertThat(greenhouseRow.company()).isEqualTo("Acme Corp");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JobListing jobListingRow(String source, String jobUrl, String applyUrl) {
        return new JobListing(
                1L, "1", source, "Backend Engineer", "Acme", "Remote",
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"), 1L, jobUrl, null,
                FilterVerdict.PASS, 1, null, (UserStatus) null, null, null, null, applyUrl, null,
                "desc", "hash", null, null, null, null, false, null, null, null, null, null, null);
    }
}
