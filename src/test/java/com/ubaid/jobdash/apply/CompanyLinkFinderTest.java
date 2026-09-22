package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.CompanyLinkMatch;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.CompanyLinkMatchRepository;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CompanyLinkFinder} against a real temp-file database, with a fake CLI and a fake link
 * checker - no test here runs the real binary or makes a network call.
 */
class CompanyLinkFinderTest extends AbstractStoreTest {

    private static final long RESUME_ID = 1L;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);

    private AiMatchRepository aiMatchRepository;
    private CompanyLinkMatchRepository matchRepository;
    private long runId;

    @BeforeEach
    void setUpRun() {
        aiMatchRepository = new AiMatchRepository(client);
        matchRepository = new CompanyLinkMatchRepository(client);
        runId = sweepRunRepository.create(Instant.now(), "engineer", "US", 24, false, null);
    }

    private static CompanyLinkProperties props(int batchSize) {
        return new CompanyLinkProperties(true, "sonnet", batchSize, 3, 70, 80, 3.0, Duration.ofMinutes(15));
    }

    private CompanyLinkFinder finder(FakeCli cli, int batchSize, Map<String, Integer> statuses) {
        return new CompanyLinkFinder(cli, props(batchSize), jobListingRepository, matchRepository, clock,
                url -> statuses.getOrDefault(url, 200));
    }

    /** A recommended LinkedIn row with a description - a finder candidate unless a test changes it. */
    private long linkedInJob(String linkedInId) {
        return job("linkedin", linkedInId, true);
    }

    private long job(String source, String sourceJobId, boolean recommended) {
        jobListingRepository.upsertAll(List.of(new JobCardInsert(source, sourceJobId, "Software Engineer " + sourceJobId,
                "Acme", "Seattle, WA", Instant.parse("2026-09-23T00:00:00Z"),
                "https://www.linkedin.com/jobs/view/" + sourceJobId, null, "<p>Build payment systems.</p>")), runId,
                Instant.now());
        long jobId = jobListingRepository.findIdBySourceKey(source, sourceJobId).orElseThrow();
        jobListingRepository.applyVerdicts(
                List.of(new JobListingRepository.VerdictUpdate(jobId, FilterVerdict.PASS, null)), 1);
        aiMatchRepository.upsertAll(List.of(new AiMatch(jobId, RESUME_ID, recommended, "fits", "sonnet", runId,
                Instant.now())));
        return jobId;
    }

    private static CliJsonResult answer(String... matches) {
        return new CliJsonResult.Ok(JsonMapper.builder().build().readTree(
                "{\"matches\":[" + String.join(",", matches) + "]}"), 0.18, 1000);
    }

    private static String match(String linkedInId, String url, int confidence) {
        return "{\"jobId\":\"" + linkedInId + "\",\"url\":\"" + url + "\",\"confidence\":" + confidence
                + ",\"matchedOn\":[\"title\",\"location\"],\"note\":\"compared title and location\"}";
    }

    private JobListing row(long jobId) {
        return jobListingRepository.findById(jobId).orElseThrow();
    }

    private CompanyLinkMatch matchFor(long jobId) {
        return matchRepository.findByJobIds(List.of(jobId)).get(jobId);
    }

    @Test
    void aConfidentMatchBecomesTheApplyLinkAWeakOneIsOnlyRecordedAndNotFoundIsRemembered() {
        long confident = linkedInJob("101");
        long weak = linkedInJob("102");
        long notFound = linkedInJob("103");
        FakeCli cli = new FakeCli(prompt -> answer(
                match("101", "https://www.amazon.jobs/en/jobs/10523955/sde", 95),
                match("102", "https://jobs.ashbyhq.com/acme/abc", 60),
                match("103", "", 20)));

        CompanyLinkFinder.Progress progress = finder(cli, 4, Map.of()).find(runId, RESUME_ID, () -> false).orElseThrow();

        assertThat(row(confident).applyUrl()).isEqualTo("https://www.amazon.jobs/en/jobs/10523955/sde");
        assertThat(row(confident).applyDomain()).isEqualTo("amazon.jobs");
        assertThat(row(confident).applyMatchNote()).startsWith("company site, 95% match:");
        assertThat(matchFor(confident).confidence()).isEqualTo(95);
        assertThat(matchFor(confident).matchedOn()).containsExactly("title", "location");

        // Below min-confidence (70): shown in the Match column, never handed to Apply with Claude.
        assertThat(row(weak).applyUrl()).isNull();
        assertThat(matchFor(weak).url()).isEqualTo("https://jobs.ashbyhq.com/acme/abc");
        assertThat(matchFor(weak).confidence()).isEqualTo(60);

        assertThat(row(notFound).applyUrl()).isNull();
        assertThat(matchFor(notFound).found()).isFalse();

        assertThat(progress.total()).isEqualTo(3);
        assertThat(progress.done()).isEqualTo(3);
        assertThat(progress.found()).isEqualTo(2);
        assertThat(progress.linked()).isEqualTo(1);
        assertThat(progress.running()).isFalse();
        // Every job has a row now, so a second search has nothing left to look for.
        assertThat(jobListingRepository.findCompanyLinkCandidates(runId, RESUME_ID)).isEmpty();
    }

    @Test
    void linkedInAggregatorAndDeadLinksAreDroppedWhateverTheConfidence() {
        long onLinkedIn = linkedInJob("201");
        long onIndeed = linkedInJob("202");
        long dead = linkedInJob("203");
        FakeCli cli = new FakeCli(prompt -> answer(
                match("201", "https://www.linkedin.com/jobs/view/201", 99),
                match("202", "https://www.indeed.com/viewjob?jk=1", 95),
                match("203", "https://careers.acme.com/jobs/203", 90)));

        finder(cli, 4, Map.of("https://careers.acme.com/jobs/203", 404)).find(runId, RESUME_ID, () -> false);

        for (long jobId : List.of(onLinkedIn, onIndeed, dead)) {
            assertThat(row(jobId).applyUrl()).isNull();
            assertThat(matchFor(jobId).found()).isFalse();
            assertThat(matchFor(jobId).note()).startsWith("Dropped: ");
        }
        assertThat(matchFor(onLinkedIn).note()).contains("www.linkedin.com");
        assertThat(matchFor(dead).note()).contains("answered 404");
    }

    @Test
    void jobsAnUnreportedOrFailedCallLeftBehindAreSearchedAgainNextTime() {
        long unreported = linkedInJob("301");
        long failed = linkedInJob("302");
        FakeCli cli = new FakeCli(prompt -> prompt.contains("JOB 301")
                ? answer()
                : new CliJsonResult.Failed("claude CLI reported an error", 1, 0.05));

        CompanyLinkFinder.Progress progress = finder(cli, 1, Map.of()).find(runId, RESUME_ID, () -> false).orElseThrow();

        assertThat(cli.prompts()).hasSize(2);
        assertThat(matchFor(unreported)).isNull();
        assertThat(matchFor(failed)).isNull();
        assertThat(progress.costUsd()).isEqualTo(0.23, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(jobListingRepository.findCompanyLinkCandidates(runId, RESUME_ID))
                .extracting(JobListing::jobId).containsExactlyInAnyOrder(unreported, failed);
    }

    @Test
    void onlyRecommendedUntriagedLinkedInJobsWithNoLinkAreSearched() {
        long candidate = linkedInJob("401");
        job("linkedin", "402", false);                                  // not recommended
        long triaged = linkedInJob("403");
        jobListingRepository.setUserStatus(triaged, UserStatus.NOT_INTERESTED, Instant.now());
        long linked = linkedInJob("404");
        jobListingRepository.setApplyTarget(linked, "https://jobs.lever.co/acme/x", "lever", "pasted URL");
        job("greenhouse", "405", true);                                 // not LinkedIn
        long searched = linkedInJob("406");
        matchRepository.save(new CompanyLinkMatch(searched, "", 10, List.of(), "nothing", Instant.now()));

        FakeCli cli = new FakeCli(prompt -> answer());
        finder(cli, 10, Map.of()).find(runId, RESUME_ID, () -> false);

        assertThat(jobListingRepository.findCompanyLinkCandidates(runId, RESUME_ID))
                .extracting(JobListing::jobId).containsExactly(candidate);
        assertThat(cli.prompts()).hasSize(1);
        assertThat(cli.prompts().get(0)).contains("JOB 401").doesNotContain("JOB 402", "JOB 403", "JOB 404", "JOB 405",
                "JOB 406");
    }

    @Test
    void jobsAreBatchedAndEachCallGetsWebToolsOnlyWithLinkedInBlocked() {
        for (int i = 0; i < 9; i++) {
            linkedInJob(String.valueOf(500 + i));
        }
        FakeCli cli = new FakeCli(prompt -> answer());

        finder(cli, 4, Map.of()).find(runId, RESUME_ID, () -> false);

        assertThat(cli.prompts()).hasSize(3);
        String allPrompts = String.join("\n", cli.prompts());
        for (int i = 0; i < 9; i++) {
            assertThat(allPrompts).contains("JOB " + (500 + i));
        }
        // The prompt describes the job; it never hands Claude the LinkedIn URL to open.
        assertThat(allPrompts).doesNotContain("linkedin.com/jobs/view").contains("TITLE: Software Engineer 500")
                .contains("POSTED ON LINKEDIN: 2026-09-23").contains("Build payment systems.");
        List<String> args = cli.args().get(0);
        assertThat(args).containsSubsequence("--tools", "WebSearch", "WebFetch");
        assertThat(args).containsSubsequence("--disallowedTools", "WebFetch(domain:linkedin.com)",
                "WebFetch(domain:www.linkedin.com)");
        assertThat(args).contains("--safe-mode", "--strict-mcp-config").doesNotContain("--chrome");
    }

    @Test
    void aSecondSearchWaitsItsTurnWhileOneIsRunning() throws InterruptedException {
        linkedInJob("601");
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeCli cli = new FakeCli(prompt -> {
            inCall.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return answer(match("601", "https://careers.acme.com/jobs/601", 88));
        });
        CompanyLinkFinder finder = finder(cli, 4, Map.of());

        assertThat(finder.startInBackground(runId, RESUME_ID)).hasValue(1);
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(finder.isRunning()).isTrue();
        assertThat(finder.find(runId, RESUME_ID, () -> false)).isEmpty();
        assertThat(finder.startInBackground(runId, RESUME_ID)).isEmpty();

        release.countDown();
        for (int i = 0; i < 100 && finder.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertThat(finder.isRunning()).isFalse();
        assertThat(finder.progress().orElseThrow().linked()).isEqualTo(1);
    }

    @Test
    void rejectReasonKnowsTheEmployersSiteFromLinkedInAndAggregators() {
        assertThat(CompanyLinkFinder.rejectReason("https://careers.walmart.com/us/en/jobs/R-2586222")).isNull();
        assertThat(CompanyLinkFinder.rejectReason("https://walmart.wd5.myworkdayjobs.com/x")).isNull();
        assertThat(CompanyLinkFinder.rejectReason("https://uk.linkedin.com/jobs/view/1")).contains("uk.linkedin.com");
        assertThat(CompanyLinkFinder.rejectReason("https://www.glassdoor.com/job-listing/x")).isNotNull();
        assertThat(CompanyLinkFinder.rejectReason("mailto:jobs@acme.com")).isEqualTo("not a web link");
    }

    /** Records every call; answers with {@code responder}. */
    private static final class FakeCli extends ClaudeCliClient {
        private final Function<String, CliJsonResult> responder;
        private final List<String> prompts = new ArrayList<>();
        private final List<List<String>> args = new ArrayList<>();

        FakeCli(Function<String, CliJsonResult> responder) {
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, false, 200),
                    JsonMapper.builder().build());
            this.responder = responder;
        }

        @Override
        public CliJsonResult runStructured(String prompt, String jsonSchema, CliOptions options) {
            synchronized (prompts) {
                prompts.add(prompt);
                args.add(options.args());
            }
            return responder.apply(prompt);
        }

        List<String> prompts() {
            synchronized (prompts) {
                return List.copyOf(prompts);
            }
        }

        List<List<String>> args() {
            synchronized (prompts) {
                return List.copyOf(args);
            }
        }
    }
}
