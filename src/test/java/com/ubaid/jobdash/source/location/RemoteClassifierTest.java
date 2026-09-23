package com.ubaid.jobdash.source.location;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.JobCardInsert;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test here invokes the real {@code claude} binary: the CLI is faked by overriding
 * {@code runStructured}, the same way {@link LocationClassifierTest} does.
 */
class RemoteClassifierTest extends AbstractStoreTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AiProperties props(int remoteBatchSize) {
        return new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, true, 200,
                remoteBatchSize);
    }

    /** Records each call's jobs (the prompt's JOBS array) and replies with whatever the responder builds. */
    private static final class FakeCli extends ClaudeCliClient {
        private final List<List<JsonNode>> jobsPerCall = new ArrayList<>();
        private final Function<List<JsonNode>, String> responder;

        FakeCli(Function<List<JsonNode>, String> responder) {
            super(props(40), JSON);
            this.responder = responder;
        }

        @Override
        public CliJsonResult runStructured(String prompt, String jsonSchema) {
            List<JsonNode> jobs = new ArrayList<>();
            JSON.readTree(prompt.substring(prompt.indexOf("JOBS:") + "JOBS:".length())).forEach(jobs::add);
            jobsPerCall.add(jobs);
            return new CliJsonResult.Ok(JSON.readTree(responder.apply(jobs)), 0.05, 100);
        }

        int invocationCount() {
            return jobsPerCall.size();
        }
    }

    /** remote=true for any job whose excerpts or location say "fully remote" / "Remote - US". */
    private static String byContent(List<JsonNode> jobs) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < jobs.size(); i++) {
            JsonNode job = jobs.get(i);
            String text = job.path("excerpts").asString("") + " " + job.path("location").asString("");
            boolean remote = text.contains("fully remote") || text.contains("Remote - US");
            sb.append(i > 0 ? "," : "")
              .append("{\"ref\":\"").append(job.path("ref").asString("")).append("\",\"remote\":").append(remote)
              .append(",\"note\":\"").append(remote ? "fully remote" : "hybrid").append("\"}");
        }
        return sb.append("]}").toString();
    }

    private RemoteClassifier classifier(ClaudeCliClient cli, int remoteBatchSize) {
        return new RemoteClassifier(jobListingRepository, cli, props(remoteBatchSize), JSON);
    }

    private long insertPassingJob(String sourceJobId, String location, String description) {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-24T00:00:00Z"), "engineer", "", 24,
                false, null, "linkedin", null);
        jobListingRepository.upsertAll(List.of(new JobCardInsert("linkedin", sourceJobId, "Engineer", "Acme",
                location, Instant.parse("2026-09-23T00:00:00Z"), "https://x/" + sourceJobId, null, description)),
                runId, Instant.parse("2026-09-24T00:00:00Z"));
        long jobId = client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", sourceJobId).query(Long.class).single();
        client.sql("update job_listing set filter_verdict = 'pass' where job_id = :id").param("id", jobId).update();
        return jobId;
    }

    private Map<String, Object> remoteOf(long jobId) {
        return client.sql("select remote, remote_note from job_listing where job_id = :id")
                .param("id", jobId).query().singleRow();
    }

    /** Most postings never say "remote"; those are settled for free. Asserting the call count proves it. */
    @Test
    void aPostingThatNeverMentionsRemoteWorkIsDecidedWithoutACliCall() {
        long job = insertPassingJob("1", "Austin, TX", "Build services in our Austin office with a great team.");

        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        RemoteClassifier.ClassifyResult result = classifier(cli, 40).classifyPending(() -> false);

        assertThat(cli.invocationCount()).isZero();
        assertThat(result.withoutCli()).isEqualTo(1);
        assertThat(remoteOf(job)).containsEntry("remote", 0)
                .containsEntry("remote_note", RemoteClassifier.NO_MENTION_NOTE);
    }

    @Test
    void claudesVerdictAndNoteAreStampedOntoEachRow() {
        long remote = insertPassingJob("1", "New York, NY", "<p>This position is fully remote within the US.</p>");
        long hybrid = insertPassingJob("2", "New York, NY", "Hybrid: remote on Fridays, in the office Monday-Thursday.");

        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        RemoteClassifier.ClassifyResult result = classifier(cli, 40).classifyPending(() -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(result.classified()).isEqualTo(2);
        assertThat(result.remote()).isEqualTo(1);
        assertThat(remoteOf(remote)).containsEntry("remote", 1).containsEntry("remote_note", "fully remote");
        assertThat(remoteOf(hybrid)).containsEntry("remote", 0).containsEntry("remote_note", "hybrid");
    }

    /** An ATS row often says it only in the location field; that alone makes it worth asking. */
    @Test
    void aRemoteLocationWithASilentDescriptionIsStillAsked() {
        long job = insertPassingJob("1", "Remote - US", "Build great software.");

        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        classifier(cli, 40).classifyPending(() -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(remoteOf(job)).containsEntry("remote", 1);
    }

    /** Correlating by position rather than the echoed ref would attach each verdict to the wrong job. */
    @Test
    void verdictsReturnedOutOfOrderStillLandOnTheRightJob() {
        long remote = insertPassingJob("1", "Seattle, WA", "This role is fully remote.");
        long hybrid = insertPassingJob("2", "Seattle, WA", "Hybrid, remote two days a week.");

        FakeCli cli = new FakeCli(jobs -> {
            List<JsonNode> reversed = new ArrayList<>(jobs);
            java.util.Collections.reverse(reversed);
            return byContent(reversed);
        });
        classifier(cli, 40).classifyPending(() -> false);

        assertThat(remoteOf(remote)).containsEntry("remote", 1);
        assertThat(remoteOf(hybrid)).containsEntry("remote", 0);
    }

    @Test
    void candidatesAreSentInBatchesOfTheConfiguredSize() {
        insertPassingJob("1", "Seattle, WA", "fully remote");
        insertPassingJob("2", "Seattle, WA", "fully remote");
        insertPassingJob("3", "Seattle, WA", "fully remote");

        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        classifier(cli, 2).classifyPending(() -> false);

        assertThat(cli.jobsPerCall).extracting(List::size).containsExactly(2, 1);
    }

    /** A row is decided once: the second pass has nothing to send. */
    @Test
    void aDecidedRowIsNeverSentAgain() {
        insertPassingJob("1", "Seattle, WA", "This role is fully remote.");
        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        RemoteClassifier classifier = classifier(cli, 40);

        classifier.classifyPending(() -> false);
        RemoteClassifier.ClassifyResult second = classifier.classifyPending(() -> false);

        assertThat(cli.invocationCount()).isEqualTo(1);
        assertThat(second.decided()).isZero();
    }

    /** A missing CLI leaves the asked rows undecided (retried next time), and never throws. */
    @Test
    void aCliFailureLeavesAskedRowsUndecided() {
        long asked = insertPassingJob("1", "Seattle, WA", "This role is fully remote.");
        long silent = insertPassingJob("2", "Seattle, WA", "Office in Seattle.");
        ClaudeCliClient failing = new ClaudeCliClient(props(40), JSON) {
            @Override
            public CliJsonResult runStructured(String prompt, String jsonSchema) {
                return new CliJsonResult.CliNotFound("claude not installed");
            }
        };

        RemoteClassifier.ClassifyResult result = classifier(failing, 40).classifyPending(() -> false);

        assertThat(result.errorMessage()).contains("claude not installed");
        assertThat(remoteOf(asked).get("remote")).as("must stay NULL so it is retried").isNull();
        assertThat(remoteOf(silent)).containsEntry("remote", 0);
    }

    /** Rejected rows are never shown, and a row without a description has nothing to read yet. */
    @Test
    void rejectedRowsAndRowsWithoutADescriptionAreLeftAlone() {
        long rejected = insertPassingJob("1", "Seattle, WA", "This role is fully remote.");
        client.sql("update job_listing set filter_verdict = 'reject' where job_id = :id").param("id", rejected).update();
        long noDescription = insertPassingJob("2", "Seattle, WA", null);

        FakeCli cli = new FakeCli(RemoteClassifierTest::byContent);
        classifier(cli, 40).classifyPending(() -> false);

        assertThat(cli.invocationCount()).isZero();
        assertThat(remoteOf(rejected).get("remote")).isNull();
        assertThat(remoteOf(noDescription).get("remote")).isNull();
    }

    /** Claude sees the sentences around workplace words, not the whole posting. */
    @Test
    void excerptsKeepTheWorkplaceSentencesAndDropTheRest() {
        String filler = "We build payment systems at scale for millions of customers worldwide. ".repeat(40);
        String description = filler + "This is a remote role open to candidates anywhere in the US. " + filler;

        String excerpts = RemoteClassifier.excerpts(description);

        assertThat(excerpts).contains("This is a remote role open to candidates anywhere in the US.");
        assertThat(excerpts.length()).isLessThan(700);
    }

    @Test
    void excerptsAreCappedWhenWorkplaceWordsAreEverywhere() {
        String description = ("Our office is great. " + "x".repeat(600) + " ").repeat(20);

        String excerpts = RemoteClassifier.excerpts(description);

        assertThat(excerpts.length()).isLessThanOrEqualTo(RemoteClassifier.MAX_EXCERPT_CHARS);
        assertThat(excerpts).doesNotEndWith(" … ");
    }
}
