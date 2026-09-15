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
                Duration.ofMinutes(3), tempDir.resolve("apply-logs").toString());
    }

    private ApplyOrchestrator orchestrator(FakeCliClient cli) {
        return new ApplyOrchestrator(cli, props(), new ApplyPromptBuilder(),
                new ApplyUrlResolver(atsCompanyRepository), applyBatchRepository,
                applicationRepository, jobListingRepository, resumeRepository, applicantProfileRepository, clock);
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
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, false, 200),
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

    private static CliJsonResult ok(String outcome, String summary) {
        return new CliJsonResult.Ok(
                JsonMapper.builder().build().readTree(
                        "{\"outcome\":\"" + outcome + "\",\"summary\":\"" + summary + "\"}"),
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
        assertThat(linkedinApp.notes()).isEqualTo("LinkedIn: apply manually");
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
                applicationRepository, jobListingRepository, resumeRepository, applicantProfileRepository, clock,
                fakeStarter);

        long batchId = orchestrator.start(List.of(firstJob, secondJob), resumeId, false);
        orchestrator.awaitLastBatch();

        assertThat(startCount.get()).isEqualTo(1);
        assertThat(stopCount.get()).isEqualTo(1);
        assertThat(events.get(0)).isEqualTo("start");
        assertThat(events.get(events.size() - 1)).isEqualTo("stop");

        List<JobApplication> apps = applicationRepository.findByBatch(batchId);
        assertThat(apps).extracting(JobApplication::status).contains("failed");
    }
}
