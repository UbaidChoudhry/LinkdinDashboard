package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.store.ApplicantProfileRepository;
import com.ubaid.jobdash.store.ApplicationRepository;
import com.ubaid.jobdash.store.ApplyBatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives "Apply with Claude": for a set of jobs, opens each posting in a Claude-in-Chrome browser
 * session, fills the application form from the applicant profile and resume, and either stops
 * before the final Submit button (default) or submits, depending on the caller's {@code submit}
 * flag. LinkedIn rows are skipped entirely (ATS boards only) with no CLI call.
 * <p>
 * Like {@code ai.ResumeMatchService}, this is best-effort by design and <b>must never throw</b>:
 * a failing job is marked {@code failed} and the batch continues; only a missing resume/profile
 * up front fails the whole batch. {@link #start} kicks off {@link #runBatch} on a virtual thread
 * and returns immediately with the batch id - the caller (and the UI) polls the {@code apply_batch}
 * row for progress, there is no in-memory registry to keep in sync.
 * <p>
 * Each non-LinkedIn job's CLI call is a {@code --output-format stream-json} run with its own
 * generated {@code --session-id}, so it stays resumable ({@code claude --resume <id> --chrome}) and
 * observable in real time: every event the CLI emits is appended to a per-job transcript file
 * under {@code apply.transcript-dir} (default {@code logs/apply/}) as it happens, rather than only
 * being known once the whole call exits. <b>The per-job transcript WILL contain values Claude
 * typed into form fields</b> - that is precisely what the user needs to debug a stuck application
 * (the applicant's name, email, work-authorization answer, etc. can end up in a tool call's
 * arguments) - {@code logs/} is gitignored, and this is a deliberate tradeoff distinct from the
 * prompt/resume/profile text, which must still never reach {@code apply.log}.
 */
@Service
public class ApplyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ApplyOrchestrator.class);

    /**
     * Human-readable apply activity, routed to its own {@code logs/apply.log} by
     * logback-spring.xml. <b>Never log the prompt, the resume text, or the applicant profile
     * through this</b> - title, company, outcome, cost and timing only.
     */
    private static final Logger applyLog = LoggerFactory.getLogger("jobdash.apply");

    /** {@code HH:mm:ss} in the machine's local zone - the transcript is read by a person alongside
     * {@code logs/apply.log}, which logback stamps in local time; UTC here made the two disagree. */
    private static final DateTimeFormatter TRANSCRIPT_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ClaudeCliClient cliClient;
    private final ApplyProperties properties;
    private final ApplyPromptBuilder promptBuilder;
    private final ApplyBatchRepository applyBatchRepository;
    private final ApplicationRepository applicationRepository;
    private final JobListingRepository jobListingRepository;
    private final ResumeRepository resumeRepository;
    private final ApplicantProfileRepository applicantProfileRepository;
    private final Clock clock;

    /** The batch currently in flight, if any - its cancel flag lives here, not in the DB. */
    private volatile InFlight inFlight;

    /** The most recently spawned batch thread - exposed only so tests can join it deterministically. */
    private volatile Thread lastBatchThread;

    public ApplyOrchestrator(ClaudeCliClient cliClient, ApplyProperties properties,
                              ApplyPromptBuilder promptBuilder, ApplyBatchRepository applyBatchRepository,
                              ApplicationRepository applicationRepository, JobListingRepository jobListingRepository,
                              ResumeRepository resumeRepository, ApplicantProfileRepository applicantProfileRepository,
                              Clock clock) {
        this.cliClient = cliClient;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.applyBatchRepository = applyBatchRepository;
        this.applicationRepository = applicationRepository;
        this.jobListingRepository = jobListingRepository;
        this.resumeRepository = resumeRepository;
        this.applicantProfileRepository = applicantProfileRepository;
        this.clock = clock;
    }

    /**
     * Creates the batch (status {@code running}) and one {@code queued} job_application row per
     * job id, then runs the batch sequentially on a virtual thread. Returns the batch id
     * immediately; the caller polls {@code apply_batch}/{@code job_application} for progress.
     */
    public long start(List<Long> jobIds, long resumeId, boolean submit) {
        Instant now = clock.instant();
        long batchId = applyBatchRepository.create(resumeId, submit, jobIds.size(), now);

        List<Long> applicationIds = new ArrayList<>();
        for (Long jobId : jobIds) {
            applicationIds.add(applicationRepository.create(batchId, jobId, "queued"));
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        this.inFlight = new InFlight(batchId, cancelled);

        this.lastBatchThread = Thread.ofVirtual().start(() -> {
            try {
                runBatch(batchId, jobIds, applicationIds, resumeId, submit, cancelled);
            } finally {
                if (this.inFlight != null && this.inFlight.batchId() == batchId) {
                    this.inFlight = null;
                }
            }
        });

        return batchId;
    }

    /**
     * Blocks until the most recently started batch's background thread finishes. Package-visible
     * purely for tests: {@link #start} must return immediately in production, but a test needs a
     * deterministic point at which the batch (run against a fast fake CLI) is guaranteed done.
     */
    void awaitLastBatch() throws InterruptedException {
        Thread thread = this.lastBatchThread;
        if (thread != null) {
            thread.join();
        }
    }

    /** Requests cancellation of an in-flight batch. Returns false if it isn't the running batch. */
    public boolean cancel(long batchId) {
        InFlight current = this.inFlight;
        if (current == null || current.batchId() != batchId) {
            return false;
        }
        current.cancelled().set(true);
        return true;
    }

    /** The batch id currently running, if any. */
    public Optional<Long> inFlightBatchId() {
        InFlight current = this.inFlight;
        return current == null ? Optional.empty() : Optional.of(current.batchId());
    }

    /** Cancels any in-flight batch on shutdown, so a killed backend never leaves Claude driving the browser. */
    @PreDestroy
    public void onShutdown() {
        InFlight current = this.inFlight;
        if (current != null) {
            current.cancelled().set(true);
        }
    }

    /**
     * Runs one batch's jobs sequentially in the given order. Package-visible so tests can run it
     * synchronously (rather than racing the virtual thread {@link #start} spawns) for deterministic
     * assertions.
     */
    void runBatch(long batchId, List<Long> jobIds, List<Long> applicationIds, long resumeId, boolean submit,
                  AtomicBoolean cancelled) {
        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        ApplicantProfile profile = applicantProfileRepository.find().orElse(null);
        if (resume == null || profile == null) {
            applyLog.warn("batch {} FAILED - {}", batchId,
                    resume == null ? "resume not found" : "applicant profile not set");
            applyBatchRepository.finish(batchId, "failed", clock.instant());
            return;
        }

        int done = 0;
        int submittedCount = 0;
        int needsReviewCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        double totalCostUsd = 0.0;
        String finalStatus = "ok";

        for (int i = 0; i < jobIds.size(); i++) {
            long jobId = jobIds.get(i);
            long applicationId = applicationIds.get(i);

            if (cancelled.get()) {
                finalStatus = "cancelled";
                break;
            }

            JobListing job = jobListingRepository.findById(jobId).orElse(null);
            if (job == null) {
                failedCount++;
                applicationRepository.update(applicationId, "failed", "job no longer exists", 0.0,
                        clock.instant(), clock.instant());
                done++;
                applyBatchRepository.updateProgress(batchId, done, submittedCount, needsReviewCount, failedCount,
                        skippedCount, totalCostUsd);
                continue;
            }

            try {
                if ("linkedin".equals(job.source())) {
                    skippedCount++;
                    applicationRepository.update(applicationId, "skipped", "LinkedIn: apply manually", 0.0,
                            clock.instant(), clock.instant());
                    applyLog.info("job {} \"{}\" at \"{}\" SKIPPED (LinkedIn - apply manually)",
                            jobId, job.title(), job.company());
                } else {
                    Instant startedAt = clock.instant();
                    applicationRepository.update(applicationId, "filling", "", 0.0, startedAt, null);

                    String sessionId = UUID.randomUUID().toString();
                    Path transcriptPath = ensureTranscriptDir().resolve(
                            "batch-" + batchId + "-job-" + jobId + ".log");
                    applicationRepository.setSession(applicationId, sessionId, transcriptPath.toString());

                    Path resumeAbsolutePath = Path.of(resume.storedPath()).toAbsolutePath();
                    String prompt = promptBuilder.build(job, resume, resumeAbsolutePath, profile, submit,
                            properties.maxDescriptionChars());
                    List<String> args = chromeArgs(properties, ApplyPromptBuilder.APPLY_JSON_SCHEMA, sessionId);
                    ClaudeCliClient.StreamOptions options = new ClaudeCliClient.StreamOptions(
                            args, properties.timeout(), properties.idleTimeout(), cancelled::get);

                    long callStart = System.nanoTime();
                    CliJsonResult result = cliClient.runStreaming(prompt, ApplyPromptBuilder.APPLY_JSON_SCHEMA,
                            options, event -> onEvent(jobId, applicationId, transcriptPath, event));
                    long tookMs = (System.nanoTime() - callStart) / 1_000_000;

                    Outcome outcome = mapResult(result, sessionId);
                    totalCostUsd += outcome.costUsd();
                    switch (outcome.status()) {
                        case "submitted" -> submittedCount++;
                        case "needs_review" -> needsReviewCount++;
                        default -> failedCount++;
                    }

                    Instant finishedAt = clock.instant();
                    applicationRepository.update(applicationId, outcome.status(), outcome.notes(),
                            outcome.costUsd(), startedAt, finishedAt);

                    if ("submitted".equals(outcome.status())) {
                        jobListingRepository.setUserStatus(jobId, UserStatus.APPLIED, finishedAt);
                    }

                    applyLog.info("job {} \"{}\" at \"{}\" outcome={} cost=${} {}ms",
                            jobId, job.title(), job.company(), outcome.status(),
                            String.format("%.4f", outcome.costUsd()), tookMs);
                }
            } catch (RuntimeException e) {
                failedCount++;
                log.warn("apply job {} threw: {}", jobId, e.toString());
                applicationRepository.update(applicationId, "failed", "unexpected error: " + e.getMessage(), 0.0,
                        clock.instant(), clock.instant());
            }

            done++;
            applyBatchRepository.updateProgress(batchId, done, submittedCount, needsReviewCount, failedCount,
                    skippedCount, totalCostUsd);
        }

        applyBatchRepository.finish(batchId, finalStatus, clock.instant());
        applyLog.info("batch {} DONE status={} done={}/{} submitted={} needs-review={} failed={} skipped={} cost=${}",
                batchId, finalStatus, done, jobIds.size(), submittedCount, needsReviewCount, failedCount,
                skippedCount, String.format("%.4f", totalCostUsd));
    }

    /**
     * Delivered one {@link ClaudeCliClient.CliEvent} at a time while a job's streaming CLI call is
     * in flight: appends a timestamped line to the per-job transcript, mirrors {@code tool}/
     * {@code tool_error} events (INFO) and {@code text} events (DEBUG) to {@code jobdash.apply},
     * and records the latest activity text on the row so the UI can show live progress. Never
     * throws - {@link ClaudeCliClient#runStreaming} already isolates listener failures, but every
     * step here (file I/O, the repository call) is defensive on top of that.
     */
    private void onEvent(long jobId, long applicationId, Path transcriptPath, ClaudeCliClient.CliEvent event) {
        String line = TRANSCRIPT_TIME.format(clock.instant()) + " " + event.kind() + " " + event.text();
        appendToTranscript(transcriptPath, line);

        switch (event.kind()) {
            case "tool", "tool_error" -> applyLog.info("job {} {} {}", jobId, event.kind(), event.text());
            case "text" -> applyLog.debug("job {} {} {}", jobId, event.kind(), event.text());
            default -> {
                // "done" (the final result line) - already surfaced via the batch's own outcome log.
            }
        }

        if ("tool".equals(event.kind()) || "tool_error".equals(event.kind()) || "text".equals(event.kind())) {
            try {
                applicationRepository.setLastActivity(applicationId, event.text());
            } catch (RuntimeException e) {
                log.debug("failed to record last activity for application {}: {}", applicationId, e.toString());
            }
        }
    }

    private static void appendToTranscript(Path transcriptPath, String line) {
        try {
            Files.writeString(transcriptPath, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("failed to append to apply transcript {}: {}", transcriptPath, e.toString());
        }
    }

    /** Creates {@code apply.transcript-dir} if it doesn't already exist, and returns it. */
    private Path ensureTranscriptDir() {
        Path dir = Path.of(properties.transcriptDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.debug("failed to create apply transcript dir {}: {}", dir, e.toString());
        }
        return dir;
    }

    /** Maps one CLI result into a status/notes/cost triple, never throwing. */
    private static Outcome mapResult(CliJsonResult result, String sessionId) {
        return switch (result) {
            case CliJsonResult.Ok ok -> {
                JsonNode structured = ok.structuredOutput();
                String outcome = structured.path("outcome").asString("failed");
                String summary = structured.path("summary").asString("");
                String unanswered = joinUnanswered(structured.get("unanswered"));
                String notes = unanswered.isEmpty() ? summary : summary + " Unanswered: " + unanswered;
                String status = switch (outcome) {
                    case "submitted" -> "submitted";
                    case "needs_review" -> "needs_review";
                    case "not_found" -> "failed";
                    default -> "failed";
                };
                String finalNotes = "not_found".equals(outcome) && summary.isEmpty()
                        ? "Posting not found (closed or 404)"
                        : notes;
                yield new Outcome(status, finalNotes, ok.costUsd());
            }
            case CliJsonResult.Timeout timeout ->
                    new Outcome("failed", "claude CLI timed out after " + timeout.afterMs() + "ms", 0.0);
            case CliJsonResult.Failed failed -> new Outcome("failed", stuckNotes(failed.message(), sessionId), 0.0);
            case CliJsonResult.CliNotFound notFound -> new Outcome("failed", notFound.message(), 0.0);
        };
    }

    /**
     * A {@code Failed} whose message starts with "no activity" (the idle-timeout kill) gets a
     * "Stuck:" note pointing at the resume command, so the UI can surface it directly - every
     * other failure message is passed through unchanged.
     */
    private static String stuckNotes(String message, String sessionId) {
        if (message == null || !message.startsWith("no activity")) {
            return message;
        }
        return "Stuck: " + message + ". Resume the session in a terminal to see the page: "
                + "claude --resume " + sessionId + " --chrome";
    }

    private static String joinUnanswered(JsonNode unanswered) {
        if (unanswered == null || !unanswered.isArray() || unanswered.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        unanswered.forEach(n -> items.add(n.asString("")));
        return String.join("; ", items);
    }

    /**
     * The CLI flag list for the Chrome-driving apply call, isolated in its own package-visible
     * static method so it can be adjusted after a live probe without touching the rest of the
     * orchestration logic. {@code stream-json}/{@code --verbose} (rather than the whole-blob
     * {@code json} format) is what makes live events possible, and {@code --session-id} (with no
     * {@code --no-session-persistence}) is what makes the run resumable afterward.
     */
    static List<String> chromeArgs(ApplyProperties p, String jsonSchema, String sessionId) {
        return List.of(
                "-p",
                "--chrome",
                "--model", p.model(),
                "--output-format", "stream-json",
                "--verbose",
                "--json-schema", jsonSchema,
                "--session-id", sessionId,
                "--strict-mcp-config",
                "--tools", "Read",
                "--allowedTools", "mcp__claude-in-chrome", "Read",
                "--max-turns", String.valueOf(p.maxTurns()),
                "--max-budget-usd", String.valueOf(p.maxBudgetUsd()));
    }

    private record InFlight(long batchId, AtomicBoolean cancelled) {
    }

    private record Outcome(String status, String notes, double costUsd) {
    }
}
