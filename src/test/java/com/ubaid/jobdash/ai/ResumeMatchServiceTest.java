package com.ubaid.jobdash.ai;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises {@link ResumeMatchService} against a real (temp-file) database via
 * {@link AbstractStoreTest}, with a fake {@link ClaudeCliClient} standing in for the real
 * binary — no test here may invoke the real CLI or touch the network. Per HANDOFF.md §1 these
 * assert the positive outcome (verdicts actually persisted, correlated to the right job) rather
 * than merely "it didn't throw."
 */
class ResumeMatchServiceTest extends AbstractStoreTest {

    private ResumeRepository resumeRepository;
    private AiMatchRepository aiMatchRepository;
    private MatchPromptBuilder promptBuilder;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUpAdditional() {
        resumeRepository = new ResumeRepository(client);
        aiMatchRepository = new AiMatchRepository(client);
    }

    private AiProperties props(int batchSize, int concurrency) {
        return props(batchSize, concurrency, false);
    }

    private AiProperties props(int batchSize, int concurrency, boolean usOnly) {
        return new AiProperties(true, "unused", "sonnet", batchSize, concurrency, Duration.ofSeconds(10), 6000, usOnly, 200, 40);
    }

    private ResumeMatchService service(AiProperties properties, ClaudeCliClient cli) {
        promptBuilder = new MatchPromptBuilder(properties, JsonMapper.builder().build());
        return new ResumeMatchService(resumeRepository, jobListingRepository, aiMatchRepository, promptBuilder,
                cli, properties, clock);
    }

    private long insertResume() {
        return resumeRepository.insert(new Resume(0, "r1", "resume.pdf", "application/pdf", "resumes/1.pdf",
                "Experienced backend engineer, Java, Kubernetes.", 45, false, Instant.now()));
    }

    /** Inserts a job on {@code runId} with the given description, and marks it filter-pass / untriaged. */
    private long insertJob(long runId, long sourceJobId, String description) {
        return insertJob(runId, sourceJobId, description, "Remote");
    }

    private long insertJob(long runId, long sourceJobId, String description, String location) {
        JobCardInsert card = new JobCardInsert("lever", String.valueOf(sourceJobId), "Backend Engineer",
                "Acme Corp", location, Instant.parse("2026-08-27T00:00:00Z"),
                "https://acme.com/jobs/" + sourceJobId, "https://acme.com", description);
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        long jobId = client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
        jobListingRepository.applyVerdicts(
                List.of(new JobListingRepository.VerdictUpdate(jobId, FilterVerdict.PASS, null)), 1);
        return jobId;
    }

    private long newRun() {
        return sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
    }

    /** A fake CLI client whose response to each call is computed from the refs it was asked to score. */
    private static final class FakeCliClient extends ClaudeCliClient {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<List<String>> refsPerInvocation = new ArrayList<>();
        private final Function<List<String>, ClaudeCliResult> responder;

        FakeCliClient(Function<List<String>, ClaudeCliResult> responder) {
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, false, 200, 40),
                    JsonMapper.builder().build());
            this.responder = responder;
        }

        @Override
        public ClaudeCliResult run(String prompt) {
            invocations.incrementAndGet();
            List<String> refs = extractRefs(prompt);
            synchronized (refsPerInvocation) {
                refsPerInvocation.add(refs);
            }
            return responder.apply(refs);
        }

        int invocationCount() {
            return invocations.get();
        }

        List<List<String>> refsPerInvocation() {
            synchronized (refsPerInvocation) {
                return List.copyOf(refsPerInvocation);
            }
        }

        /** Pulls every {@code "ref":"..."} value out of the prompt's embedded jobs JSON, in order. */
        private static List<String> extractRefs(String prompt) {
            List<String> refs = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"ref\"\\s*:\\s*\"([^\"]*)\"").matcher(prompt);
            while (m.find()) {
                refs.add(m.group(1));
            }
            return refs;
        }
    }

    @Test
    void jobsWithoutDescriptionAreNeverSentToTheCli() {
        long resumeId = insertResume();
        long runId = newRun();
        long withDescription = insertJob(runId, 1, "Backend role requiring Java and Kubernetes.");
        insertJob(runId, 2, ""); // blank description - the LinkedIn case, must not be scanned

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(cli.refsPerInvocation().get(0)).containsExactly(String.valueOf(withDescription));
        assertThat(result.scanned()).isEqualTo(1);
    }

    @Test
    void secondScanOfSamePairMakesZeroCliInvocations() {
        long resumeId = insertResume();
        long runId = newRun();
        insertJob(runId, 10, "Backend role requiring Java.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult first = service.scan(runId, resumeId, () -> false);
        assertThat(first.scanned()).isEqualTo(1);
        assertThat(cli.invocationCount()).isEqualTo(1);

        ResumeMatchService.ScanResult second = service.scan(runId, resumeId, () -> false);

        assertThat(second.scanned()).isEqualTo(0);
        assertThat(cli.invocationCount()).isEqualTo(1); // unchanged - no new CLI call
    }

    @Test
    void failedBatchDoesNotPreventOtherBatchesFromPersisting() {
        long resumeId = insertResume();
        long runId = newRun();
        long failingJob = insertJob(runId, 20, "Job that will fail to score.");
        long okJob1 = insertJob(runId, 21, "Job that will score fine, one.");
        long okJob2 = insertJob(runId, 22, "Job that will score fine, two.");

        String failingRef = String.valueOf(failingJob);
        FakeCliClient cli = new FakeCliClient(refs -> {
            if (refs.contains(failingRef)) {
                return new ClaudeCliResult.Failed("model overloaded", 1);
            }
            return new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 100);
        });
        // batch size 1 so each job is its own batch and the failure is isolated to one batch.
        ResumeMatchService service = service(props(1, 3), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(result.failedBatches()).isEqualTo(1);
        Map<Long, AiMatch> matches = aiMatchRepository.findByJobIds(List.of(failingJob, okJob1, okJob2), resumeId);
        assertThat(matches).doesNotContainKey(failingJob);
        assertThat(matches).containsKeys(okJob1, okJob2);
    }

    @Test
    void cliNotFoundStopsTheScanAndSurfacesAnActionableErrorMessage() {
        long resumeId = insertResume();
        long runId = newRun();
        insertJob(runId, 30, "Job one.");
        insertJob(runId, 31, "Job two.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.CliNotFound("Claude CLI not found at 'unused'. Install the Claude CLI."));
        ResumeMatchService service = service(props(1, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(result.errorMessage()).isNotNull();
        assertThat(result.errorMessage()).contains("Claude CLI");
        assertThat(aiMatchRepository.findByJobIds(List.of(), resumeId)).isEmpty();
    }

    @Test
    void verdictsAreCorrelatedByRefEvenWhenReturnedOutOfOrder() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobA = insertJob(runId, 40, "Strong Java match.");
        long jobB = insertJob(runId, 41, "Weak match, wrong stack.");
        long jobC = insertJob(runId, 42, "Medium match.");

        // The CLI deliberately returns verdicts in reverse order, with distinguishable reasons,
        // to prove correlation happens by ref rather than by response position.
        FakeCliClient cli = new FakeCliClient(refs -> {
            List<MatchVerdict> reversed = new ArrayList<>();
            for (int i = refs.size() - 1; i >= 0; i--) {
                String ref = refs.get(i);
                boolean recommended = ref.equals(String.valueOf(jobA));
                reversed.add(new MatchVerdict(ref, recommended, "verdict-for-" + ref));
            }
            return new ClaudeCliResult.Ok(reversed, 0.02, 200);
        });
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        Map<Long, AiMatch> matches = aiMatchRepository.findByJobIds(List.of(jobA, jobB, jobC), resumeId);
        assertThat(matches.get(jobA).recommended()).isTrue();
        assertThat(matches.get(jobA).reason()).isEqualTo("verdict-for-" + jobA);
        assertThat(matches.get(jobB).recommended()).isFalse();
        assertThat(matches.get(jobB).reason()).isEqualTo("verdict-for-" + jobB);
        assertThat(matches.get(jobC).recommended()).isFalse();
        assertThat(matches.get(jobC).reason()).isEqualTo("verdict-for-" + jobC);
    }

    @Test
    void batchingRespectsConfiguredBatchSize() {
        long resumeId = insertResume();
        long runId = newRun();
        for (int i = 0; i < 25; i++) {
            insertJob(runId, 100 + i, "Job number " + i + " description.");
        }

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 3), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(3); // ceil(25 / 9)
        assertThat(result.scanned()).isEqualTo(25);
    }

    // ---- live scan progress ------------------------------------------------------------------

    @Test
    void reportsProgressAsEachBatchCompletes() {
        long resumeId = insertResume();
        long runId = newRun();
        for (int i = 0; i < 20; i++) {
            insertJob(runId, 100 + i, "Backend role requiring Java " + i);
        }

        FakeCliClient cli = new FakeCliClient(refs -> new ClaudeCliResult.Ok(
                refs.stream().map(r -> new MatchVerdict(r, Long.parseLong(r) % 2 == 0, "because")).toList(),
                0.02, 100));
        // batch size 5 over 20 jobs = 4 batches.
        ResumeMatchService service = service(props(5, 1), cli);

        List<ScanProgress> seen = Collections.synchronizedList(new ArrayList<>());
        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false, seen::add);

        // One snapshot up front carrying the totals, then one per finished batch.
        assertThat(seen).hasSize(5);
        assertThat(seen.get(0).batchesTotal()).isEqualTo(4);
        assertThat(seen.get(0).jobsTotal()).isEqualTo(20);
        assertThat(seen.get(0).batchesDone()).isZero();

        ScanProgress last = seen.get(seen.size() - 1);
        assertThat(last.batchesDone()).isEqualTo(4);
        assertThat(last.jobsScanned()).isEqualTo(20);
        assertThat(last.recommended() + last.notRecommended()).isEqualTo(20);
        assertThat(last.recommended()).isEqualTo(result.recommended());
        assertThat(last.notRecommended()).isEqualTo(result.notRecommended());
        assertThat(last.costUsd()).isEqualTo(0.08, within(1e-9));
        assertThat(last.startedAt()).isNotNull();

        // batchesDone must never go backwards - the UI renders it as forward progress.
        for (int i = 1; i < seen.size(); i++) {
            assertThat(seen.get(i).batchesDone()).isGreaterThanOrEqualTo(seen.get(i - 1).batchesDone());
        }
    }

    @Test
    void countsAFailedBatchInProgressWithoutLosingTheOthers() {
        long resumeId = insertResume();
        long runId = newRun();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(insertJob(runId, 200 + i, "Backend role " + i));
        }
        // refs are the surrogate job_id, not the source id, so capture the real one to key on.
        String failingRef = String.valueOf(ids.get(0));

        // The batch containing the first job fails; the other must still be counted.
        FakeCliClient cli = new FakeCliClient(refs -> refs.contains(failingRef)
                ? new ClaudeCliResult.Failed("boom", 1)
                : new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 50));
        ResumeMatchService service = service(props(5, 1), cli);

        List<ScanProgress> seen = Collections.synchronizedList(new ArrayList<>());
        service.scan(runId, resumeId, () -> false, seen::add);

        ScanProgress last = seen.get(seen.size() - 1);
        assertThat(last.failedBatches()).isEqualTo(1);
        assertThat(last.batchesDone()).isEqualTo(2);
        assertThat(last.jobsScanned()).as("the surviving batch's verdicts still count").isEqualTo(5);
    }

    /**
     * A listener is UI plumbing. If it throws, the scan itself must still complete and persist -
     * otherwise a rendering bug in the dashboard could silently cost real scan results.
     */
    @Test
    void aListenerThatThrowsDoesNotBreakTheScan() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 300, "Backend role requiring Java.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 50));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false, p -> {
            throw new IllegalStateException("listener blew up");
        });

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(aiMatchRepository.findByJobIds(List.of(jobId), resumeId)).hasSize(1);
    }

    // ---- location guard, now enforced by the repository query --------------------------------

    /** Sets the classifier's verdict on one row, the way LocationClassifier does after a run. */
    private void setLocationVerdict(long jobId, boolean inUs, boolean confident) {
        client.sql("update job_listing set location_us = :us, location_confident = :c where job_id = :id")
                .param("us", inUs ? 1 : 0)
                .param("c", confident ? 1 : 0)
                .param("id", jobId)
                .update();
    }

    /**
     * A confidently non-US posting must never reach Claude. The guard now lives in
     * {@code findScannableByRun}'s SQL rather than in this service, so this asserts on the CLI
     * invocation content — proving the posting never left the machine, not merely that no verdict
     * was stored.
     */
    @Test
    void confidentlyNonUsJobsAreNeverSentToTheCli() {
        long resumeId = insertResume();
        long runId = newRun();
        long austin = insertJob(runId, 400, "Backend role requiring Java.", "US - Austin, TX");
        long israel = insertJob(runId, 401, "Backend role requiring Java.", "Israel, Yokneam");
        setLocationVerdict(austin, true, true);
        setLocationVerdict(israel, false, true);

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 50));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(cli.refsPerInvocation().get(0))
                .as("only the US posting may reach the CLI")
                .containsExactly(String.valueOf(austin));
        assertThat(result.scanned()).isEqualTo(1);
    }

    /**
     * The two states that must stay scannable: a row Claude has not judged yet (the CLI was
     * unavailable, or the row predates classification), and one it judged with low confidence.
     * Hiding either would silently shrink the user's results on a guess.
     */
    @Test
    void unclassifiedAndLowConfidenceJobsAreStillScanned() {
        long resumeId = insertResume();
        long runId = newRun();
        long unclassified = insertJob(runId, 500, "Backend role.", "11 Locations");
        long lowConfidence = insertJob(runId, 501, "Backend role.", "San Jose");
        setLocationVerdict(lowConfidence, false, false);
        // `unclassified` is left with location_us NULL on purpose.

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 50));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(cli.refsPerInvocation().get(0))
                .containsExactlyInAnyOrder(String.valueOf(unclassified), String.valueOf(lowConfidence));
    }

    // ---- posting-stated salary extraction ----------------------------------------------------

    /** A verdict carrying a salary must land on the job row, with source stamped 'posting'. */
    @Test
    void verdictWithSalaryWritesSalaryAndPostingSourceOntoTheJob() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 700, "Backend role. Pay: $150,000 - $180,000/yr.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream()
                        .map(r -> new MatchVerdict(r, true, "fits", 150000, 180000)).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salaryMin()).isEqualTo(150000.0);
        assertThat(job.salaryMax()).isEqualTo(180000.0);
        assertThat(job.salarySource()).isEqualTo("posting");
    }

    /** A posting-stated salary is ground truth and must overwrite an existing estimate. */
    @Test
    void verdictWithSalaryOverwritesAnExistingEstimate() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 701, "Backend role. Pay: $150,000 - $180,000/yr.");
        jobListingRepository.applySalary(jobId, 90000.0, 110000.0, "adzuna", "Acme Corp Estimate");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream()
                        .map(r -> new MatchVerdict(r, true, "fits", 150000, 180000)).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salaryMin()).isEqualTo(150000.0);
        assertThat(job.salaryMax()).isEqualTo(180000.0);
        assertThat(job.salarySource()).isEqualTo("posting");
    }

    /** An inverted salary (min > max) is a hallucination signal and must be rejected outright. */
    @Test
    void invertedSalaryIsRejectedAndRowUntouched() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 702, "Backend role.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream()
                        .map(r -> new MatchVerdict(r, true, "fits", 180000, 150000)).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salarySource()).isNull();
        assertThat(job.salaryMin()).isNull();
    }

    /** A salary outside the plausible 10k-2M annual range is also a hallucination signal. */
    @Test
    void outOfRangeSalaryIsRejectedAndRowUntouched() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 703, "Backend role.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream()
                        .map(r -> new MatchVerdict(r, true, "fits", 1, 5)).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salarySource()).isNull();
        assertThat(job.salaryMin()).isNull();
    }

    /** A verdict without a stated salary must leave the job's salary columns untouched. */
    @Test
    void verdictWithoutSalaryLeavesSalaryColumnsAlone() {
        long resumeId = insertResume();
        long runId = newRun();
        long jobId = insertJob(runId, 704, "Backend role, competitive pay.");

        FakeCliClient cli = new FakeCliClient(refs ->
                new ClaudeCliResult.Ok(refs.stream().map(r -> new MatchVerdict(r, true, "fits")).toList(), 0.01, 100));
        ResumeMatchService service = service(props(9, 1), cli);

        service.scan(runId, resumeId, () -> false);

        JobListing job = jobListingRepository.findById(jobId).orElseThrow();
        assertThat(job.salarySource()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
    }

    /** A run where every posting is confidently non-US is a clean no-op, not an error. */
    @Test
    void aRunWithNoUsJobsMakesNoCliCallsAtAll() {
        long resumeId = insertResume();
        long runId = newRun();
        setLocationVerdict(insertJob(runId, 600, "Backend role.", "Israel, Yokneam"), false, true);
        setLocationVerdict(insertJob(runId, 601, "Backend role.", "Germany - Munich"), false, true);

        FakeCliClient cli = new FakeCliClient(refs -> new ClaudeCliResult.Ok(List.of(), 0.0, 10));
        ResumeMatchService service = service(props(9, 1), cli);

        ResumeMatchService.ScanResult result = service.scan(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isZero();
        assertThat(result.scanned()).isZero();
        assertThat(result.errorMessage()).isNull();
    }
}
