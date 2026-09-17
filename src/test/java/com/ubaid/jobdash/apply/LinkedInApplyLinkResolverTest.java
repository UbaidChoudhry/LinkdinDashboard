package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LinkedInApplyLinkResolverTest extends AbstractStoreTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AiMatchRepository aiMatchRepository;
    private ResumeRepository resumeRepository;
    private long runId;
    private long resumeId;

    @BeforeEach
    void setUpResolver() {
        aiMatchRepository = new AiMatchRepository(client);
        resumeRepository = new ResumeRepository(client);
        runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        resumeId = resumeRepository.insert(new Resume(0, "r1", "resume.pdf", "application/pdf", "resumes/1.pdf",
                "Experienced backend engineer.", 30, false, Instant.now()));
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private LinkedInLinkProperties props(int batchSize, int maxPerRun) {
        return new LinkedInLinkProperties(true, batchSize, maxPerRun, 120, 2.0, Duration.ofMinutes(10));
    }

    private ApplyProperties applyProps() {
        return new ApplyProperties(true, "sonnet", Duration.ofSeconds(10), 60, 2.0, 4000,
                Duration.ofMinutes(3), tempDir.resolve("apply-logs").toString(), "medium");
    }

    private LinkedInApplyLinkResolver resolver(FakeCliClient cli, LinkedInLinkProperties props) {
        return new LinkedInApplyLinkResolver(cli, applyProps(), props, jobListingRepository, aiMatchRepository,
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC));
    }

    /** A recommended, untriaged LinkedIn row in this run with a description; returns its job_id. */
    private long linkedInJob(long sourceJobId, boolean recommended, long forResume) {
        JobCardInsert card = new JobCardInsert("linkedin", String.valueOf(sourceJobId), "Backend Engineer",
                "Acme Corp", "Remote", Instant.parse("2026-09-20T00:00:00Z"),
                "https://www.linkedin.com/jobs/view/" + sourceJobId, null, "Great role with lots of detail.");
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());
        long jobId = client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId)).query(Long.class).single();
        jobListingRepository.applyVerdicts(
                List.of(new JobListingRepository.VerdictUpdate(jobId, FilterVerdict.PASS, null)), 1);
        aiMatchRepository.upsertAll(List.of(new AiMatch(jobId, forResume, recommended, "fits", "sonnet", runId,
                Instant.now())));
        return jobId;
    }

    private long linkedInJob(long sourceJobId) {
        return linkedInJob(sourceJobId, true, resumeId);
    }

    private static CliJsonResult ok(boolean loggedIn, String... resultJsonObjects) {
        String json = "{\"loggedIn\":" + loggedIn + ",\"results\":[" + String.join(",", resultJsonObjects) + "]}";
        return new CliJsonResult.Ok(JSON.readTree(json), 0.25, 1000);
    }

    private static String external(long sourceJobId, String url) {
        return "{\"jobId\":\"" + sourceJobId + "\",\"kind\":\"external\",\"applyUrl\":\"" + url + "\",\"how\":\"href\"}";
    }

    private static String easyApply(long sourceJobId) {
        return "{\"jobId\":\"" + sourceJobId + "\",\"kind\":\"easy_apply\",\"applyUrl\":\"\",\"how\":\"button\"}";
    }

    private JobListing row(long jobId) {
        return jobListingRepository.findById(jobId).orElseThrow();
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    void splitsCandidatesIntoBatchesAndListsEachPostingInThePrompt() {
        List<Long> sourceIds = new ArrayList<>();
        for (long i = 1; i <= 17; i++) {
            linkedInJob(1000 + i);
            sourceIds.add(1000 + i);
        }
        FakeCliClient cli = new FakeCliClient(prompt -> ok(true));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(8, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(3);
        assertThat(summary.candidates()).isEqualTo(17);
        assertThat(summary.batches()).isEqualTo(3);
        String allPrompts = String.join("\n", cli.prompts);
        for (long id : sourceIds) {
            assertThat(allPrompts).contains("jobId " + id + ": https://www.linkedin.com/jobs/view/" + id);
        }
        assertThat(cli.prompts.get(0)).contains("Do NOT apply to anything");
        // Every posting had no result in the (empty) reply, so each is noted as unresolved.
        assertThat(summary.unresolved()).isEqualTo(17);
        assertThat(row(jobIdFor(1001)).applyMatchNote()).isEqualTo("link not resolved: no result");
    }

    @Test
    void anExternalResultStoresTheLinkDomainKindAndNote() {
        long jobId = linkedInJob(2001);
        FakeCliClient cli = new FakeCliClient(prompt ->
                ok(true, external(2001, "https://acme.wd5.myworkdayjobs.com/External/job/Remote/Backend_R123/apply")));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(8, 40)).resolve(runId, resumeId, () -> false);

        assertThat(summary.external()).isEqualTo(1);
        assertThat(summary.costUsd()).isEqualTo(0.25);
        JobListing job = row(jobId);
        assertThat(job.applyUrl()).isEqualTo("https://acme.wd5.myworkdayjobs.com/External/job/Remote/Backend_R123/apply");
        assertThat(job.applyDomain()).isEqualTo("workday");
        assertThat(job.applyKind()).isEqualTo("offsite");
        assertThat(job.applyMatchNote()).isEqualTo("linkedin apply link (href)");
        assertThat(job.userStatus()).isNull();
    }

    @Test
    void anEasyApplyResultMarksTheRowOnsiteWithNoLink() {
        long jobId = linkedInJob(2002);
        FakeCliClient cli = new FakeCliClient(prompt -> ok(true, easyApply(2002)));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(8, 40)).resolve(runId, resumeId, () -> false);

        assertThat(summary.easyApply()).isEqualTo(1);
        JobListing job = row(jobId);
        assertThat(job.applyUrl()).isNull();
        assertThat(job.applyDomain()).isNull();
        assertThat(job.applyKind()).isEqualTo("onsite");
        assertThat(job.applyMatchNote()).isEqualTo("Easy Apply - no external link; apply manually");
    }

    @Test
    void rowsAlreadyKnownAsEasyApplyAndRowsForAnotherResumeAreNotCandidates() {
        long onsite = linkedInJob(2003);
        jobListingRepository.setApplyKind(onsite, "onsite");
        linkedInJob(2004, true, resumeId + 99);     // recommended, but for a different resume
        linkedInJob(2005, false, resumeId);         // not recommended
        FakeCliClient cli = new FakeCliClient(prompt -> ok(true));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(8, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isZero();
        assertThat(summary.candidates()).isZero();
    }

    @Test
    void aSignInWallStopsThePhaseAndNotesEveryRemainingRow() {
        long a = linkedInJob(3001);
        long b = linkedInJob(3002);
        long c = linkedInJob(3003);
        FakeCliClient cli = new FakeCliClient(prompt -> ok(false));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(2, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(summary.stoppedEarly()).isTrue();
        assertThat(summary.stopReason()).isEqualTo("not signed in");
        for (long id : List.of(a, b, c)) {
            assertThat(row(id).applyMatchNote()).isEqualTo(LinkedInApplyLinkResolver.NOT_SIGNED_IN_NOTE);
        }
    }

    @Test
    void aFailedBatchIsNotedAndTheNextBatchStillRuns() {
        long a = linkedInJob(4001);
        long b = linkedInJob(4002);
        AtomicInteger calls = new AtomicInteger();
        FakeCliClient cli = new FakeCliClient(prompt -> calls.incrementAndGet() == 1
                ? new CliJsonResult.Failed("claude stopped early: error_max_turns", 1, 0.5)
                : ok(true, external(4002, "https://jobs.lever.co/acme/1111-2222/apply")));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(1, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(2);
        assertThat(summary.stoppedEarly()).isFalse();
        assertThat(row(a).applyMatchNote()).isEqualTo("link resolution failed: claude stopped early: error_max_turns");
        assertThat(row(b).applyDomain()).isEqualTo("lever");
        assertThat(summary.costUsd()).isEqualTo(0.75);
    }

    @Test
    void aMissingCliStopsThePhase() {
        long a = linkedInJob(5001);
        long b = linkedInJob(5002);
        FakeCliClient cli = new FakeCliClient(prompt -> new CliJsonResult.CliNotFound("Claude CLI not found"));

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(1, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(summary.stoppedEarly()).isTrue();
        assertThat(row(a).applyMatchNote()).startsWith("link resolution failed:");
        assertThat(row(b).applyMatchNote()).startsWith("link resolution stopped:");
    }

    @Test
    void cancellationBetweenBatchesLeavesTheRestUntouched() {
        linkedInJob(6001);
        long later = linkedInJob(6002);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        FakeCliClient cli = new FakeCliClient(prompt -> {
            cancelled.set(true);
            return ok(true, external(6001, "https://boards.greenhouse.io/acme/jobs/1"));
        });

        LinkedInApplyLinkResolver.Summary summary = resolver(cli, props(1, 40)).resolve(runId, resumeId, cancelled::get);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(summary.stopReason()).isEqualTo("cancelled");
        assertThat(row(later).applyUrl()).isNull();
        assertThat(row(later).applyMatchNote()).isNull();
    }

    @Test
    void perRunCapNotesTheOverflowWithoutCallingTheCli() {
        linkedInJob(7001);
        long over = linkedInJob(7002);
        FakeCliClient cli = new FakeCliClient(prompt -> ok(true));

        resolver(cli, props(8, 1)).resolve(runId, resumeId, () -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(cli.prompts.get(0)).doesNotContain("jobId 7002");
        assertThat(row(over).applyMatchNote()).isEqualTo("link cap reached - retried next run");
    }

    @Test
    void cliArgsCarryChromeAndAFreshSessionPerBatch() {
        linkedInJob(8001);
        linkedInJob(8002);
        FakeCliClient cli = new FakeCliClient(prompt -> ok(true));

        resolver(cli, props(1, 40)).resolve(runId, resumeId, () -> false);

        assertThat(cli.argsPerCall).hasSize(2);
        for (List<String> args : cli.argsPerCall) {
            assertThat(args).contains("--chrome", "--session-id", "--max-turns", "120");
            assertThat(args).doesNotContain("--safe-mode");
        }
        assertThat(sessionIdOf(cli.argsPerCall.get(0))).isNotEqualTo(sessionIdOf(cli.argsPerCall.get(1)));
        assertThat(tempDir.resolve("apply-logs").resolve("links-run-" + runId + "-batch-1.log")).exists();
    }

    @Test
    void domainOfMapsTheThreeKnownAtsHostsAndKeepsOtherHosts() {
        assertThat(LinkedInApplyLinkResolver.domainOf("https://job-boards.greenhouse.io/embed/job_app?for=x&token=1"))
                .isEqualTo("greenhouse");
        assertThat(LinkedInApplyLinkResolver.domainOf("https://jobs.lever.co/acme/uuid/apply")).isEqualTo("lever");
        assertThat(LinkedInApplyLinkResolver.domainOf("https://acme.wd5.myworkdayjobs.com/x/job/y")).isEqualTo("workday");
        assertThat(LinkedInApplyLinkResolver.domainOf("https://www.jobbol.com.br/vaga/1?utm=x")).isEqualTo("jobbol.com.br");
        assertThat(LinkedInApplyLinkResolver.domainOf("not a url")).isEqualTo("other");
        assertThat(LinkedInApplyLinkResolver.domainOf(null)).isEqualTo("other");
    }

    private static String sessionIdOf(List<String> args) {
        return args.get(args.indexOf("--session-id") + 1);
    }

    private long jobIdFor(long sourceJobId) {
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId)).query(Long.class).single();
    }

    /** Captures prompts/args and answers from the given function; emits one canned event so transcripts get written. */
    private static final class FakeCliClient extends ClaudeCliClient {
        private final AtomicInteger invocations = new AtomicInteger();
        final List<String> prompts = new ArrayList<>();
        final List<List<String>> argsPerCall = new ArrayList<>();
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
            prompts.add(prompt);
            argsPerCall.add(options.args());
            listener.accept(new CliEvent("tool", "navigate https://www.linkedin.com/jobs/view/1"));
            return responder.apply(prompt);
        }

        int invocationCount() {
            return invocations.get();
        }
    }
}
