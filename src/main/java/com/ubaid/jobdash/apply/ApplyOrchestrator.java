package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.ProfileAnswer;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.domain.ApplyBatch;
import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.resume.ResumeUploadFile;
import com.ubaid.jobdash.store.ApplicantProfileRepository;
import com.ubaid.jobdash.store.ApplicationRepository;
import com.ubaid.jobdash.store.ApplyBatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ProfileAnswerRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;

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
 * A batch fills up to {@code apply.concurrency} jobs at once (the request may ask for 1 to
 * {@value #MAX_CONCURRENCY}): each job is its own CLI process and Claude session, which the
 * Claude-in-Chrome extension gives its own tab group, so jobs do not see each other's tabs. Two
 * Workday jobs on the same tenant are the exception and run one after the other
 * ({@link #exclusiveKey}).
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

    /** Terminal status for a batch the process never got to finish (see {@link OrphanedApplyBatchReaper}). */
    public static final String INTERRUPTED = "interrupted";

    /**
     * The most applications one batch fills at once. Each is a whole Claude session driving its own
     * Chrome tab group; two were verified side by side (HANDOFF.md §12), and past a handful the
     * subscription's rate limits and the user's ability to watch them are the real ceiling.
     */
    public static final int MAX_CONCURRENCY = 5;

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

    /** Date/time header format for the end-of-run markdown report - local time, same as {@link #TRANSCRIPT_TIME}. */
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Something started for the duration of a batch and stopped when it's done - in production,
     * the running {@code caffeinate} process; in tests, a fake that just records the calls.
     */
    interface KeepAwake {
        void stop();
    }

    /** Starts a {@link KeepAwake} for one batch. Package-visible so tests can substitute a fake. */
    @FunctionalInterface
    interface KeepAwakeStarter {
        KeepAwake start();
    }

    private static final KeepAwake NO_OP_KEEP_AWAKE = () -> { };

    /**
     * {@code caffeinate -i} (not {@code -d}) prevents only idle sleep for as long as the process
     * lives - the display may still sleep, but the Mac itself won't, so an hours-long batch of
     * Claude-in-Chrome applications isn't cut off mid-form by the machine going to sleep. Absent
     * on non-Mac systems, so this is a no-op wherever the binary isn't there or fails to start.
     */
    private static final KeepAwakeStarter DEFAULT_KEEP_AWAKE_STARTER = () -> {
        try {
            if (Files.isExecutable(Path.of("/usr/bin/caffeinate"))) {
                Process process = new ProcessBuilder("/usr/bin/caffeinate", "-i").start();
                return process::destroy;
            }
        } catch (IOException e) {
            log.debug("failed to start caffeinate: {}", e.toString());
        }
        return NO_OP_KEEP_AWAKE;
    };

    private final ClaudeCliClient cliClient;
    private final ApplyProperties properties;
    private final ApplyPromptBuilder promptBuilder;
    private final ApplyUrlResolver applyUrlResolver;
    private final ApplyBatchRepository applyBatchRepository;
    private final ApplicationRepository applicationRepository;
    private final JobListingRepository jobListingRepository;
    private final ResumeRepository resumeRepository;
    private final ApplicantProfileRepository applicantProfileRepository;
    private final ProfileAnswerRepository profileAnswerRepository;
    private final FormQuestionPrefetcher formQuestionPrefetcher;
    private final Clock clock;
    private final KeepAwakeStarter keepAwakeStarter;

    /** Serializes {@code profile_answer} bookkeeping between a batch's parallel jobs; see {@link #applyOne}. */
    private final Object questionLock = new Object();

    /** The batch currently in flight, if any - its cancel flag lives here, not in the DB. */
    private volatile InFlight inFlight;

    /** The most recently spawned batch thread - exposed only so tests can join it deterministically. */
    private volatile Thread lastBatchThread;

    @Autowired
    public ApplyOrchestrator(ClaudeCliClient cliClient, ApplyProperties properties,
                              ApplyPromptBuilder promptBuilder, ApplyUrlResolver applyUrlResolver,
                              ApplyBatchRepository applyBatchRepository,
                              ApplicationRepository applicationRepository, JobListingRepository jobListingRepository,
                              ResumeRepository resumeRepository, ApplicantProfileRepository applicantProfileRepository,
                              ProfileAnswerRepository profileAnswerRepository,
                              FormQuestionPrefetcher formQuestionPrefetcher, Clock clock) {
        this(cliClient, properties, promptBuilder, applyUrlResolver, applyBatchRepository, applicationRepository,
                jobListingRepository, resumeRepository, applicantProfileRepository, profileAnswerRepository,
                formQuestionPrefetcher, clock, DEFAULT_KEEP_AWAKE_STARTER);
    }

    /**
     * Package-visible seam for tests: lets a test substitute a fake {@link KeepAwakeStarter} so it
     * can assert start/stop without spawning the real {@code caffeinate} binary.
     */
    ApplyOrchestrator(ClaudeCliClient cliClient, ApplyProperties properties,
                       ApplyPromptBuilder promptBuilder, ApplyUrlResolver applyUrlResolver,
                       ApplyBatchRepository applyBatchRepository,
                       ApplicationRepository applicationRepository, JobListingRepository jobListingRepository,
                       ResumeRepository resumeRepository, ApplicantProfileRepository applicantProfileRepository,
                       ProfileAnswerRepository profileAnswerRepository, FormQuestionPrefetcher formQuestionPrefetcher,
                       Clock clock, KeepAwakeStarter keepAwakeStarter) {
        this.cliClient = cliClient;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.applyUrlResolver = applyUrlResolver;
        this.applyBatchRepository = applyBatchRepository;
        this.applicationRepository = applicationRepository;
        this.jobListingRepository = jobListingRepository;
        this.resumeRepository = resumeRepository;
        this.applicantProfileRepository = applicantProfileRepository;
        this.profileAnswerRepository = profileAnswerRepository;
        this.formQuestionPrefetcher = formQuestionPrefetcher;
        this.clock = clock;
        this.keepAwakeStarter = keepAwakeStarter;
    }

    /** As {@link #start(List, long, boolean, int)} at the configured {@code apply.concurrency}. */
    public long start(List<Long> jobIds, long resumeId, boolean submit) {
        return start(jobIds, resumeId, submit, properties.concurrency());
    }

    /**
     * Creates the batch (status {@code running}) and one {@code queued} job_application row per
     * job id, then runs the batch on a virtual thread, filling up to {@code concurrency} jobs at
     * once (clamped to 1..{@value #MAX_CONCURRENCY}). Returns the batch id immediately; the caller
     * polls {@code apply_batch}/{@code job_application} for progress.
     */
    public long start(List<Long> jobIds, long resumeId, boolean submit, int concurrency) {
        int workers = Math.clamp(concurrency, 1, MAX_CONCURRENCY);
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
                runBatch(batchId, jobIds, applicationIds, resumeId, submit, workers, cancelled);
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

    /**
     * Finishes every {@code running} batch this process is <b>not</b> actually running, marking
     * each of its {@code queued}/{@code filling} jobs {@code failed} with {@code notes} and the
     * batch itself {@code terminalStatus}. Returns how many batches were closed. Used at startup
     * (a batch orphaned by a restart) and by Cancel when the batch it is asked to cancel is such
     * an orphan - the two ways a dead batch otherwise blocks every new one with a 409.
     */
    public int reapOrphanedBatches(String terminalStatus, String notes) {
        Optional<Long> live = inFlightBatchId();
        int reaped = 0;
        for (ApplyBatch batch : applyBatchRepository.findRunning()) {
            if (live.isPresent() && live.get() == batch.id()) {
                continue;
            }
            Instant now = clock.instant();
            for (JobApplication application : applicationRepository.findByBatch(batch.id())) {
                if ("queued".equals(application.status()) || "filling".equals(application.status())) {
                    applicationRepository.update(application.id(), "failed", notes, application.costUsd(),
                            application.startedAt() == null ? now : application.startedAt(), now);
                }
            }
            applyBatchRepository.finish(batch.id(), terminalStatus, now);
            applyLog.warn("batch {} closed as {} - {}", batch.id(), terminalStatus, notes);
            reaped++;
        }
        return reaped;
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
     * Runs one batch: {@code concurrency} worker threads each take the next job (in the given
     * order) from a {@link JobQueue} and fill it, until the queue is empty or the batch is
     * cancelled. Every job is its own Claude session in its own Chrome tab group, so jobs are
     * independent - except two Workday jobs on the same tenant, which the queue never hands out at
     * the same time (see {@link #exclusiveKey}). Package-visible so tests can run it synchronously
     * (rather than racing the virtual thread {@link #start} spawns) for deterministic assertions.
     */
    void runBatch(long batchId, List<Long> jobIds, List<Long> applicationIds, long resumeId, boolean submit,
                  int concurrency, AtomicBoolean cancelled) {
        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        ApplicantProfile profile = applicantProfileRepository.find().orElse(null);
        if (resume == null || profile == null) {
            applyLog.warn("batch {} FAILED - {}", batchId,
                    resume == null ? "resume not found" : "applicant profile not set");
            applyBatchRepository.finish(batchId, "failed", clock.instant());
            return;
        }

        // Made once for the whole batch: the attached file's name is what the employer sees, so
        // attach the copy that carries the original upload name, not the id-named file in
        // data/resumes/ - and parallel jobs must not race each other refreshing that copy.
        Path resumeAbsolutePath = ResumeUploadFile.forUpload(resume);
        Map<Long, JobListing> jobs = jobListingRepository.findByIds(jobIds);
        BatchTally tally = new BatchTally(batchId, jobIds.size());
        JobQueue queue = new JobQueue(jobIds.size(), i -> exclusiveKey(jobs.get(jobIds.get(i))));
        int workerCount = Math.clamp(concurrency, 1, Math.max(1, jobIds.size()));
        applyLog.info("batch {} START jobs={} at-a-time={} submit={}", batchId, jobIds.size(), workerCount, submit);

        KeepAwake keepAwake = keepAwakeStarter.start();
        try {
            List<Thread> workers = new ArrayList<>();
            for (int w = 0; w < workerCount; w++) {
                workers.add(Thread.ofVirtual().start(() -> {
                    OptionalInt next;
                    while ((next = queue.take(cancelled)).isPresent()) {
                        int i = next.getAsInt();
                        try {
                            tally.record(i, applyOne(batchId, jobIds.get(i), applicationIds.get(i),
                                    jobs.get(jobIds.get(i)), resume, resumeAbsolutePath, profile, submit, cancelled));
                        } finally {
                            queue.release(i);
                        }
                    }
                }));
            }
            for (Thread worker : workers) {
                joinUninterruptibly(worker, cancelled);
            }
        } finally {
            keepAwake.stop();
        }

        String finalStatus = cancelled.get() ? "cancelled" : "ok";
        Instant finishInstant = clock.instant();
        applyBatchRepository.finish(batchId, finalStatus, finishInstant);

        BatchTally.Totals totals = tally.totals();
        Path reportPath = writeReport(batchId, submit, finishInstant, totals);
        applyBatchRepository.setReport(batchId, totals.newQuestions(), reportPath == null ? null : reportPath.toString());

        applyLog.info("batch {} DONE status={} done={}/{} submitted={} needs-review={} failed={} skipped={} "
                        + "new-questions={} cost=${}",
                batchId, finalStatus, totals.done(), jobIds.size(), totals.submitted(), totals.needsReview(),
                totals.failed(), totals.skipped(), totals.newQuestions(), String.format("%.4f", totals.costUsd()));
    }

    /**
     * Fills one job and returns what happened to it; never throws - an unexpected exception is
     * that one job's {@code failed} result, never the batch's. Runs on a worker thread, possibly
     * alongside other jobs of the same batch.
     */
    private JobResult applyOne(long batchId, long jobId, long applicationId, JobListing job, Resume resume,
                               Path resumeAbsolutePath, ApplicantProfile profile, boolean submit,
                               AtomicBoolean cancelled) {
        if (job == null) {
            applicationRepository.update(applicationId, "failed", "job no longer exists", 0.0,
                    clock.instant(), clock.instant());
            return JobResult.of(new ReportRow("job #" + jobId, "", "failed", 0.0, false, null));
        }

        try {
            if ("linkedin".equals(job.source()) && job.applyDomain() == null) {
                applicationRepository.update(applicationId, "skipped",
                        "LinkedIn: no company-site match - apply manually", 0.0,
                        clock.instant(), clock.instant());
                applyLog.info("job {} \"{}\" at \"{}\" SKIPPED (LinkedIn - no company-site match)",
                        jobId, job.title(), job.company());
                return JobResult.of(new ReportRow(job.title(), job.company(), "skipped", 0.0, false, null));
            }

            Instant startedAt = clock.instant();
            applicationRepository.update(applicationId, "filling", "", 0.0, startedAt, null);

            boolean matchedLinkedin = "linkedin".equals(job.source()) && job.applyDomain() != null;
            String postingUrl = matchedLinkedin ? job.applyUrl() : job.jobUrl();

            String sessionId = UUID.randomUUID().toString();
            Path transcriptPath = ensureTranscriptDir().resolve("batch-" + batchId + "-job-" + jobId + ".log");
            applicationRepository.setSession(applicationId, sessionId, transcriptPath.toString());

            if (matchedLinkedin) {
                appendToTranscript(transcriptPath, TRANSCRIPT_TIME.format(clock.instant()) + " info "
                        + "linkedin → " + job.applyDomain() + " " + job.applyUrl());
            }

            Optional<String> directFormUrl = applyUrlResolver.directFormUrl(job);
            appendToTranscript(transcriptPath, TRANSCRIPT_TIME.format(clock.instant()) + " info "
                    + directFormUrl.map(url -> "direct form url: " + url).orElse("no direct form url"));

            List<String> formQuestions = directFormUrl.map(formQuestionPrefetcher::prefetch).orElse(List.of());
            appendToTranscript(transcriptPath, TRANSCRIPT_TIME.format(clock.instant()) + " info "
                    + "pre-read " + formQuestions.size() + " form questions");

            List<ProfileAnswer> answers = profileAnswerRepository.list();

            String prompt = promptBuilder.build(job, resume, resumeAbsolutePath, profile, submit,
                    properties.maxDescriptionChars(), postingUrl, directFormUrl, answers, formQuestions);
            List<String> args = chromeArgs(properties, ApplyPromptBuilder.APPLY_JSON_SCHEMA, sessionId);
            ClaudeCliClient.StreamOptions options = new ClaudeCliClient.StreamOptions(
                    args, properties.timeout(), properties.idleTimeout(), cancelled::get);

            long callStart = System.nanoTime();
            CliJsonResult result = cliClient.runStreaming(prompt, ApplyPromptBuilder.APPLY_JSON_SCHEMA,
                    options, event -> onEvent(jobId, applicationId, transcriptPath, event));
            long tookMs = (System.nanoTime() - callStart) / 1_000_000;

            Outcome outcome = mapResult(result, sessionId);
            Instant finishedAt = clock.instant();
            applicationRepository.update(applicationId, outcome.status(), outcome.notes(),
                    outcome.costUsd(), startedAt, finishedAt);

            if ("submitted".equals(outcome.status())) {
                jobListingRepository.setUserStatus(jobId, UserStatus.APPLIED, finishedAt);
            }

            // A pasted URL's row only had a placeholder title and a guessed company until now.
            String title = job.title();
            String company = job.company();
            if (PastedUrlJobs.SOURCE.equals(job.source()) && !outcome.jobTitle().isBlank()) {
                title = outcome.jobTitle();
                company = outcome.company().isBlank() ? company : outcome.company();
                jobListingRepository.setPastedTitleAndCompany(jobId, title, company);
            }

            // recordUnanswered reads, then inserts or bumps: two jobs finishing at once with the
            // same new question would otherwise both insert it.
            List<PendingReportEntry> pending = new ArrayList<>();
            int newQuestions = 0;
            synchronized (questionLock) {
                for (String question : outcome.unanswered()) {
                    ProfileAnswerRepository.Recorded recorded = profileAnswerRepository.recordUnanswered(
                            question, jobId, company, finishedAt);
                    if (recorded.created()) {
                        newQuestions++;
                    }
                    pending.add(new PendingReportEntry(question, title, company));
                }
            }

            applyLog.info("job {} \"{}\" at \"{}\" outcome={} cost=${} {}ms",
                    jobId, title, company, outcome.status(), String.format("%.4f", outcome.costUsd()), tookMs);

            String resumeCommand = "claude --resume " + sessionId + " --chrome";
            ReportRow row = new ReportRow(title, company, outcome.status(), outcome.costUsd(),
                    "needs_review".equals(outcome.status()), resumeCommand);
            return new JobResult(row, pending, newQuestions);
        } catch (RuntimeException e) {
            log.warn("apply job {} threw: {}", jobId, e.toString());
            applicationRepository.update(applicationId, "failed", "unexpected error: " + e.getMessage(), 0.0,
                    clock.instant(), clock.instant());
            return JobResult.of(new ReportRow(job.title(), job.company(), "failed", 0.0, false, null));
        }
    }

    /**
     * The key two jobs must not share while running, or null if a job can run beside anything.
     * Two Workday jobs on one tenant share that tenant's signed-in session and account, so a
     * second tab reaching the sign-in page mid-way through the first one's form is kept from
     * happening at all - a precaution, not a failure seen in a transcript. Greenhouse and Lever
     * forms are independent pages and never wait on each other.
     */
    static String exclusiveKey(JobListing job) {
        if (job == null) {
            return null;
        }
        String url = job.applyUrl() != null && !job.applyUrl().isBlank() ? job.applyUrl() : job.jobUrl();
        try {
            String host = url == null ? null : URI.create(url.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".myworkdayjobs.com") || host.endsWith(".myworkdaysite.com") ? host : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Joins a worker; an interrupt of the batch thread cancels the batch rather than abandoning its workers. */
    private static void joinUninterruptibly(Thread worker, AtomicBoolean cancelled) {
        while (true) {
            try {
                worker.join();
                return;
            } catch (InterruptedException e) {
                cancelled.set(true);
            }
        }
    }

    /**
     * Hands out a batch's job indexes to its workers in batch order, passing over (for now) any job
     * whose {@link #exclusiveKey} another worker holds. A worker with nothing it may start waits
     * for a key to be released; cancellation or an empty queue sends every worker home.
     */
    static final class JobQueue {
        private final List<Integer> pending = new ArrayList<>();
        private final Set<String> busy = new HashSet<>();
        private final IntFunction<String> keyOf;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition released = lock.newCondition();

        JobQueue(int size, IntFunction<String> keyOf) {
            for (int i = 0; i < size; i++) {
                pending.add(i);
            }
            this.keyOf = keyOf;
        }

        /** The next job index this worker may start, or empty when there is none left (or the batch was cancelled). */
        OptionalInt take(AtomicBoolean cancelled) {
            lock.lock();
            try {
                while (!pending.isEmpty() && !cancelled.get()) {
                    for (Iterator<Integer> it = pending.iterator(); it.hasNext(); ) {
                        int index = it.next();
                        String key = keyOf.apply(index);
                        if (key == null || busy.add(key)) {
                            it.remove();
                            return OptionalInt.of(index);
                        }
                    }
                    // Everything left waits on a key someone holds; re-check cancel now and then.
                    released.await(500, TimeUnit.MILLISECONDS);
                }
                return OptionalInt.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return OptionalInt.empty();
            } finally {
                lock.unlock();
            }
        }

        /** Frees the key of a job that has finished, waking any worker waiting on it. */
        void release(int index) {
            lock.lock();
            try {
                String key = keyOf.apply(index);
                if (key != null) {
                    busy.remove(key);
                }
                released.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Everything a batch accumulates across its jobs. Jobs finish on different worker threads, so
     * each result - and the {@code apply_batch} progress write that publishes it - goes through
     * one lock. Report rows are kept by job index so the report lists jobs in batch order, not in
     * whatever order they happened to finish.
     */
    private final class BatchTally {
        private final long batchId;
        private final JobResult[] results;
        private int done;
        private int submitted;
        private int needsReview;
        private int failed;
        private int skipped;
        private int newQuestions;
        private double costUsd;

        BatchTally(long batchId, int size) {
            this.batchId = batchId;
            this.results = new JobResult[size];
        }

        synchronized void record(int index, JobResult result) {
            results[index] = result;
            switch (result.row().outcome()) {
                case "submitted" -> submitted++;
                case "needs_review" -> needsReview++;
                case "skipped" -> skipped++;
                default -> failed++;
            }
            costUsd += result.row().costUsd();
            newQuestions += result.newQuestions();
            done++;
            applyBatchRepository.updateProgress(batchId, done, submitted, needsReview, failed, skipped, costUsd);
        }

        synchronized Totals totals() {
            List<ReportRow> rows = new ArrayList<>();
            List<PendingReportEntry> pending = new ArrayList<>();
            for (JobResult result : results) {
                if (result != null) {
                    rows.add(result.row());
                    pending.addAll(result.pending());
                }
            }
            return new Totals(rows, pending, done, submitted, needsReview, failed, skipped, newQuestions, costUsd);
        }

        record Totals(List<ReportRow> rows, List<PendingReportEntry> pending, int done, int submitted,
                      int needsReview, int failed, int skipped, int newQuestions, double costUsd) {
        }
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

    /** Maps one CLI result into a status/notes/cost/unanswered-list quadruple, never throwing. */
    private Outcome mapResult(CliJsonResult result, String sessionId) {
        return switch (result) {
            case CliJsonResult.Ok ok -> {
                JsonNode structured = ok.structuredOutput();
                String outcome = structured.path("outcome").asString("failed");
                String summary = structured.path("summary").asString("");
                List<String> unansweredList = unansweredList(structured.get("unanswered"));
                String jobTitle = structured.path("jobTitle").asString("").strip();
                String company = structured.path("company").asString("").strip();
                String unanswered = String.join("; ", unansweredList);
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
                yield new Outcome(status, finalNotes, ok.costUsd(), unansweredList, jobTitle, company);
            }
            case CliJsonResult.Timeout timeout ->
                    new Outcome("failed", "claude CLI timed out after " + timeout.afterMs() + "ms", 0.0, List.of(),
                            "", "");
            case CliJsonResult.Failed failed ->
                    new Outcome("failed", stuckNotes(failed.message(), sessionId), failed.costUsd(), List.of(),
                            "", "");
            case CliJsonResult.CliNotFound notFound ->
                    new Outcome("failed", notFound.message(), 0.0, List.of(), "", "");
        };
    }

    /**
     * A {@code Failed} whose message starts with "no activity" (the idle-timeout kill) gets a
     * "Stuck:" note, and one containing {@code error_max_turns} or {@code error_max_budget} (the
     * CLI stopping itself early) gets a note explaining which ceiling was hit - each points at the
     * resume command so the user can pick the application back up where Claude left off. Every
     * other failure message is passed through unchanged.
     */
    private String stuckNotes(String message, String sessionId) {
        if (message == null) {
            return message;
        }
        String resumeHint = "claude --resume " + sessionId + " --chrome then tell Claude to keep filling "
                + "the application.";
        if (message.contains("error_max_turns")) {
            return "Ran out of browser actions (apply.max-turns = " + properties.maxTurns()
                    + ") before the form was finished; it is probably mostly filled. Continue it yourself: "
                    + resumeHint;
        }
        if (message.contains("error_max_budget")) {
            return "Hit the per-job cost cap (apply.max-budget-usd = " + properties.maxBudgetUsd()
                    + ") before the form was finished; it is probably mostly filled. Continue it yourself: "
                    + resumeHint;
        }
        if (message.startsWith("no activity")) {
            return "Stuck: " + message + ". Resume the session in a terminal to see the page: "
                    + "claude --resume " + sessionId + " --chrome";
        }
        return message;
    }

    private static List<String> unansweredList(JsonNode unanswered) {
        if (unanswered == null || !unanswered.isArray() || unanswered.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        unanswered.forEach(n -> items.add(n.asString("")));
        return items;
    }

    /**
     * The CLI flag list for the Chrome-driving apply call, isolated in its own package-visible
     * static method so it can be adjusted after a live probe without touching the rest of the
     * orchestration logic. {@code stream-json}/{@code --verbose} (rather than the whole-blob
     * {@code json} format) is what makes live events possible, and {@code --session-id} (with no
     * {@code --no-session-persistence}) is what makes the run resumable afterward.
     */
    static List<String> chromeArgs(ApplyProperties p, String jsonSchema, String sessionId) {
        return chromeArgs(p, jsonSchema, sessionId, p.maxTurns(), p.maxBudgetUsd());
    }

    /** As above with the turn and cost caps supplied by the caller - the LinkedIn link resolver has its own. */
    static List<String> chromeArgs(ApplyProperties p, String jsonSchema, String sessionId, int maxTurns,
                                   double maxBudgetUsd) {
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
                "--max-turns", String.valueOf(maxTurns),
                "--max-budget-usd", String.valueOf(maxBudgetUsd),
                "--effort", p.effort());
    }

    private record InFlight(long batchId, AtomicBoolean cancelled) {
    }

    /** {@code jobTitle}/{@code company} are what Claude read off the page, blank when it reported none. */
    private record Outcome(String status, String notes, double costUsd, List<String> unanswered, String jobTitle,
                           String company) {
    }

    /** One job's finished result: its report row, the questions it left unanswered, and how many of those were new. */
    private record JobResult(ReportRow row, List<PendingReportEntry> pending, int newQuestions) {
        static JobResult of(ReportRow row) {
            return new JobResult(row, List.of(), 0);
        }
    }

    /** One row of the end-of-run report's job table. {@code resumeCommand} is null for skipped/missing jobs. */
    private record ReportRow(String title, String company, String outcome, double costUsd, boolean tabLeftOpen,
                              String resumeCommand) {
    }

    /** One question recorded as unanswered during this batch, for the report's questions section. */
    private record PendingReportEntry(String question, String jobTitle, String company) {
    }

    /**
     * Writes {@code logs/apply/batch-<id>-report.md} (or wherever {@code apply.transcript-dir}
     * points) summarizing every job's outcome and every question recorded as unanswered this run,
     * and returns its path - or null if writing it failed (best-effort, like everything else in
     * this class). {@code REPORT_TIME} formats the header's date in the same local zone as the
     * transcripts and {@code apply.log}.
     */
    private Path writeReport(long batchId, boolean submit, Instant now, BatchTally.Totals totals) {
        List<ReportRow> reportRows = totals.rows();
        List<PendingReportEntry> pendingEntries = totals.pending();
        Path path = ensureTranscriptDir().resolve("batch-" + batchId + "-report.md");
        StringBuilder md = new StringBuilder();
        md.append("# Apply batch ").append(batchId).append(" - ").append(REPORT_TIME.format(now))
                .append(" (submit: ").append(submit ? "on" : "off").append(")\n\n");

        md.append("## Jobs\n\n");
        md.append("| Title | Company | Outcome | Cost | Tab left open | Resume |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (ReportRow row : reportRows) {
            md.append("| ").append(row.title()).append(" | ").append(row.company()).append(" | ")
                    .append(row.outcome()).append(" | $").append(String.format("%.2f", row.costUsd()))
                    .append(" | ").append(row.tabLeftOpen() ? "yes" : "no").append(" | ")
                    .append(row.resumeCommand() == null ? "" : "`" + row.resumeCommand() + "`").append(" |\n");
        }

        if (!pendingEntries.isEmpty()) {
            md.append("\n## Questions Claude could not answer\n\n");
            for (PendingReportEntry entry : pendingEntries) {
                md.append("- \"").append(entry.question()).append("\" - asked while applying to ")
                        .append(entry.jobTitle()).append(" at ").append(entry.company()).append("\n");
            }
            md.append("\nAnswer these in the Resumes tab → Applicant profile → Questions & answers; "
                    + "the next batch will use them.\n");
        }

        md.append("\n## Totals\n\n");
        md.append("- Jobs: ").append(reportRows.size()).append("\n");
        md.append("- Submitted: ").append(totals.submitted()).append(", Needs review: ").append(totals.needsReview())
                .append(", Failed: ").append(totals.failed()).append(", Skipped: ").append(totals.skipped()).append("\n");
        md.append("- New questions: ").append(pendingEntries.size()).append("\n");
        md.append("- Total cost: $").append(String.format("%.2f", totals.costUsd())).append("\n");

        try {
            Files.writeString(path, md.toString());
            return path;
        } catch (IOException e) {
            log.debug("failed to write apply batch report {}: {}", path, e.toString());
            return null;
        }
    }
}
