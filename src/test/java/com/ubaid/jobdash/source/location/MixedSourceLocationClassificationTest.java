package com.ubaid.jobdash.source.location;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.LocationVerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the mixed-source location-classification bug: a run containing both
 * LinkedIn and ATS rows used to let location classification sweep up the LinkedIn rows too
 * (scoped only by {@code last_seen_run_id}, with no source predicate), so a confidently-non-US
 * verdict on a shared location string could hide LinkedIn postings that were never meant to
 * carry a location verdict at all - LinkedIn results are already location-scoped by the search
 * query itself. Fixed by adding {@code source <> 'linkedin'} to both
 * {@link JobListingRepository#findDistinctLocationsByRun} and
 * {@link JobListingRepository#applyLocationVerdict}.
 */
class MixedSourceLocationClassificationTest extends AbstractStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    private LocationVerdictRepository locationVerdictRepository;

    @BeforeEach
    void setUpAdditional() {
        locationVerdictRepository = new LocationVerdictRepository(client);
    }

    private LocationClassifier classifier(ClaudeCliClient cli) {
        return new LocationClassifier(jobListingRepository, locationVerdictRepository, cli,
                new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, true, 200, 40),
                JsonMapper.builder().build(), CLOCK);
    }

    private long jobIdFor(String sourceJobId) {
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", sourceJobId)
                .query(Long.class)
                .single();
    }

    private Integer locationUsOf(long jobId) {
        return client.sql("select location_us from job_listing where job_id = :id")
                .param("id", jobId).query(Integer.class).optional().orElse(null);
    }

    /** Every location this fake CLI sees is judged confidently non-US. */
    private static String allNonUsVerdicts(List<String> locations) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < locations.size(); i++) {
            sb.append(i > 0 ? "," : "")
              .append("{\"ref\":\"").append(i).append("\",\"inUs\":false,\"confident\":true}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void mixedRunClassifiesOnlyAtsRowsAndLeavesLinkedInRowsVisible() {
        long runId = sweepRunRepository.create(Instant.parse("2026-09-08T00:00:00Z"), "engineer", "", 24,
                false, null, "workday", null);

        // Same raw location string on both a LinkedIn card and a Workday card - the shared string
        // is exactly what let the bug sweep the LinkedIn row into classification.
        String sharedLocation = "Tel Aviv, Israel";
        jobListingRepository.upsertAll(List.of(
                new JobCardInsert("linkedin", "LI1", "Engineer", "Acme", sharedLocation,
                        Instant.parse("2026-09-01T00:00:00Z"), "https://linkedin.com/jobs/view/LI1",
                        "https://linkedin.com/company/acme", null),
                new JobCardInsert("workday", "JR1", "Engineer", "Acme", sharedLocation,
                        Instant.parse("2026-09-01T00:00:00Z"), "https://acme.wd/JR1", null, "desc")
        ), runId, Instant.parse("2026-09-08T00:00:00Z"));

        long linkedInJob = jobIdFor("LI1");
        long workdayJob = jobIdFor("JR1");

        // Both rows must pass the filter and carry no prior user status to be candidates for the
        // Search-tab read path exercised below.
        jobListingRepository.applyVerdicts(List.of(
                new JobListingRepository.VerdictUpdate(linkedInJob, FilterVerdict.PASS, null),
                new JobListingRepository.VerdictUpdate(workdayJob, FilterVerdict.PASS, null)), 1);

        FakeCli cli = new FakeCli(MixedSourceLocationClassificationTest::allNonUsVerdicts);
        LocationClassifier.ClassifyResult result = classifier(cli).classifyRun(runId, () -> false);

        // Only the Workday row's location was in scope for classification.
        assertThat(result.distinctLocations()).isEqualTo(1);
        assertThat(result.classified()).isEqualTo(1);
        assertThat(result.hiddenRows()).isEqualTo(1);

        // The LinkedIn row must stay untouched (NULL), not confidently-non-US...
        assertThat(locationUsOf(linkedInJob)).as("LinkedIn rows are never location-classified").isNull();
        // ...and the Workday row, sharing the exact same raw string, is correctly stamped hidden.
        assertThat(locationUsOf(workdayJob)).isZero();

        // Positive assertion (HANDOFF #1): the LinkedIn row still comes back from the Search-tab
        // read path, proving it stayed visible rather than merely lacking a hidden-row stamp.
        List<Long> visibleJobIds = jobListingRepository
                .findByRunAndVerdictAndNullUserStatus(runId, FilterVerdict.PASS)
                .stream()
                .map(job -> job.jobId())
                .toList();
        assertThat(visibleJobIds).contains(linkedInJob);
        assertThat(visibleJobIds).doesNotContain(workdayJob);
    }

    /** Captures each prompt's refs+locations and replies with whatever the responder decides. */
    private static final class FakeCli extends ClaudeCliClient {
        private final java.util.function.Function<List<String>, String> responder;

        FakeCli(java.util.function.Function<List<String>, String> responder) {
            super(new AiProperties(true, "unused", "sonnet", 9, 3, Duration.ofSeconds(10), 6000, true, 200, 40),
                    JsonMapper.builder().build());
            this.responder = responder;
        }

        @Override
        public CliJsonResult runStructured(String prompt, String jsonSchema) {
            List<String> locations = new java.util.ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"location\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(prompt);
            while (m.find()) {
                locations.add(m.group(1));
            }
            JsonNode node = JsonMapper.builder().build().readTree(responder.apply(locations));
            return new CliJsonResult.Ok(node, 0.05, 100);
        }
    }
}
