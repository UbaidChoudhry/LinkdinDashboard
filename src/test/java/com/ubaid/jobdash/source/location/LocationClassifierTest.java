package com.ubaid.jobdash.source.location;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.LocationVerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test here invokes the real {@code claude} binary. The CLI is faked by overriding
 * {@code runStructured}, mirroring how {@code ResumeMatchServiceTest} fakes {@code run}.
 */
class LocationClassifierTest extends AbstractStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    private LocationVerdictRepository locationVerdictRepository;

    @BeforeEach
    void setUpAdditional() {
        locationVerdictRepository = new LocationVerdictRepository(client);
    }

    /** Captures each prompt's refs+locations and replies with whatever the responder decides. */
    private static final class FakeCli extends ClaudeCliClient {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<List<String>> locationsPerCall = new ArrayList<>();
        private final Function<List<String>, String> responder;

        FakeCli(Function<List<String>, String> responder) {
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, true, 200, 40),
                    JsonMapper.builder().build());
            this.responder = responder;
        }

        @Override
        public CliJsonResult runStructured(String prompt, String jsonSchema) {
            invocations.incrementAndGet();
            List<String> locations = new ArrayList<>();
            Matcher m = Pattern.compile("\"location\"\\s*:\\s*\"([^\"]*)\"").matcher(prompt);
            while (m.find()) {
                locations.add(m.group(1));
            }
            synchronized (locationsPerCall) {
                locationsPerCall.add(locations);
            }
            JsonNode node = JsonMapper.builder().build().readTree(responder.apply(locations));
            return new CliJsonResult.Ok(node, 0.05, 100);
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    /** Replies inUs=true for anything mentioning US/TX, false otherwise; all confident. */
    private static String simpleVerdicts(List<String> locations) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < locations.size(); i++) {
            String loc = locations.get(i).toLowerCase();
            boolean us = loc.contains("us") || loc.contains("tx");
            sb.append(i > 0 ? "," : "")
              .append("{\"ref\":\"").append(i).append("\",\"inUs\":").append(us)
              .append(",\"confident\":true}");
        }
        return sb.append("]}").toString();
    }

    private LocationClassifier classifier(ClaudeCliClient cli) {
        return new LocationClassifier(jobListingRepository, locationVerdictRepository, cli,
                new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, true, 200, 40),
                JsonMapper.builder().build(), CLOCK);
    }

    private long newRun() {
        return sweepRunRepository.create(Instant.parse("2026-09-08T00:00:00Z"), "engineer", "", 24,
                false, null, "workday", null);
    }

    private long insertJob(long runId, String sourceJobId, String location) {
        jobListingRepository.upsertAll(List.of(new JobCardInsert("workday", sourceJobId, "Engineer", "Acme",
                location, Instant.parse("2026-09-01T00:00:00Z"), "https://x/" + sourceJobId, null, "desc")),
                runId, Instant.parse("2026-09-08T00:00:00Z"));
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", sourceJobId).query(Long.class).single();
    }

    private Integer locationUsOf(long jobId) {
        return client.sql("select location_us from job_listing where job_id = :id")
                .param("id", jobId).query(Integer.class).optional().orElse(null);
    }

    @Test
    void classifiesEachDistinctLocationAndStampsItOntoTheRows() {
        long runId = newRun();
        long austin = insertJob(runId, "JR1", "US - Austin, TX");
        long israel = insertJob(runId, "JR2", "Israel, Yokneam");

        FakeCli cli = new FakeCli(LocationClassifierTest::simpleVerdicts);
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(runId, () -> false);

        assertThat(result.distinctLocations()).isEqualTo(2);
        assertThat(result.classified()).isEqualTo(2);
        assertThat(result.hiddenRows()).isEqualTo(1);
        assertThat(locationUsOf(austin)).isEqualTo(1);
        assertThat(locationUsOf(israel)).isZero();
    }

    /**
     * The cache is the entire cost argument for this feature: distinct locations repeat heavily,
     * so a second run over the same strings must cost NOTHING. Asserting the invocation count,
     * not just the result, is what proves it.
     */
    @Test
    void aSecondRunOverTheSameLocationsMakesZeroCliCalls() {
        long firstRun = newRun();
        insertJob(firstRun, "JR1", "US - Austin, TX");
        FakeCli cli = new FakeCli(LocationClassifierTest::simpleVerdicts);
        classifier(cli).classifyRun(firstRun, () -> false);
        assertThat(cli.invocationCount()).isEqualTo(1);

        long secondRun = newRun();
        long again = insertJob(secondRun, "JR2", "US - Austin, TX");
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(secondRun, () -> false);

        assertThat(cli.invocationCount()).as("the cached string must not be re-sent").isEqualTo(1);
        assertThat(result.fromCache()).isEqualTo(1);
        assertThat(locationUsOf(again)).as("a cached verdict is still stamped onto the new row").isEqualTo(1);
    }

    /** Several spellings of one place are one classification, not several. */
    @Test
    void spellingVariantsOfOneLocationCostOneSlot() {
        long runId = newRun();
        insertJob(runId, "JR1", "US - Austin, TX");
        insertJob(runId, "JR2", "us - austin, tx  ");

        FakeCli cli = new FakeCli(LocationClassifierTest::simpleVerdicts);
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(runId, () -> false);

        assertThat(result.distinctLocations()).isEqualTo(1);
        assertThat(cli.locationsPerCall.get(0)).hasSize(1);
    }

    /**
     * The model may answer in any order. Correlating by position rather than by the echoed ref
     * would silently attach each verdict to the wrong location.
     */
    @Test
    void verdictsReturnedOutOfOrderStillLandOnTheRightLocation() {
        long runId = newRun();
        long austin = insertJob(runId, "JR1", "US - Austin, TX");
        long israel = insertJob(runId, "JR2", "Israel, Yokneam");

        FakeCli cli = new FakeCli(locations -> {
            // Deliberately reversed relative to the order sent.
            int austinIdx = locations.indexOf("US - Austin, TX");
            int israelIdx = locations.indexOf("Israel, Yokneam");
            return "{\"results\":["
                    + "{\"ref\":\"" + israelIdx + "\",\"inUs\":false,\"confident\":true},"
                    + "{\"ref\":\"" + austinIdx + "\",\"inUs\":true,\"confident\":true}]}";
        });
        classifier(cli).classifyRun(runId, () -> false);

        assertThat(locationUsOf(austin)).isEqualTo(1);
        assertThat(locationUsOf(israel)).isZero();
    }

    /**
     * A missing or failing CLI must leave rows UNCLASSIFIED, not hidden. Unclassified rows stay
     * visible and are retried next run — hiding a user's results because a binary was absent
     * would be the worst possible failure mode.
     */
    @Test
    void aCliFailureLeavesRowsUnclassifiedAndNeverThrows() {
        long runId = newRun();
        long job = insertJob(runId, "JR1", "US - Austin, TX");

        ClaudeCliClient failing = new ClaudeCliClient(
                new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(1), 6000, true, 200, 40),
                JsonMapper.builder().build()) {
            @Override
            public CliJsonResult runStructured(String prompt, String jsonSchema) {
                return new CliJsonResult.CliNotFound("claude not installed");
            }
        };

        LocationClassifier.ClassifyResult result = classifier(failing).classifyRun(runId, () -> false);

        assertThat(result.errorMessage()).contains("claude not installed");
        assertThat(locationUsOf(job)).as("must stay NULL so the row remains visible").isNull();
        assertThat(locationVerdictRepository.count()).isZero();
    }

    @Test
    void lowConfidenceVerdictsAreRecordedRatherThanDiscarded() {
        long runId = newRun();
        long job = insertJob(runId, "JR1", "11 Locations");

        FakeCli cli = new FakeCli(locations ->
                "{\"results\":[{\"ref\":\"0\",\"inUs\":true,\"confident\":false}]}");
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(runId, () -> false);

        assertThat(result.hiddenRows()).as("a low-confidence row is kept, not hidden").isZero();
        Integer confident = client.sql("select location_confident from job_listing where job_id = :id")
                .param("id", job).query(Integer.class).single();
        assertThat(confident).isZero();
    }

    @Test
    void aRunWithNoLocationsIsACleanNoOp() {
        FakeCli cli = new FakeCli(LocationClassifierTest::simpleVerdicts);
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(newRun(), () -> false);

        assertThat(cli.invocationCount()).isZero();
        assertThat(result.distinctLocations()).isZero();
        assertThat(result.errorMessage()).isNull();
    }
}
