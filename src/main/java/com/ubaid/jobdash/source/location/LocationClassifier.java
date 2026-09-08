package com.ubaid.jobdash.source.location;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.LocationVerdictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Asks Claude whether each of a run's location strings refers to the United States, and stamps the
 * answer onto the job rows.
 *
 * <p>Job boards write locations as free-form text in shapes no pattern matcher keeps up with —
 * {@code "US - Austin, TX"}, {@code "Israel, Yokneam"} (country first), {@code "USA.VA.Reston"},
 * {@code "California - Remote"}, Workday's {@code "11 Locations"} placeholder. The previous
 * hand-written matcher needed repeated correction and still got {@code "Remote - CA"} — California
 * or Canada? — confidently wrong.
 *
 * <p>Two properties make this cheap. Distinct location strings repeat heavily across postings, and
 * every decision is cached permanently in {@code location_verdict} (no TTL — a location string's
 * country does not change). So a run typically costs one CLI invocation, and often zero.
 *
 * <p><b>Never throws.</b> Any failure leaves the affected rows unclassified ({@code location_us}
 * NULL), which keeps them VISIBLE and retries them on the next run. That is deliberate: a missing
 * or slow CLI must never silently hide a user's job results, and must never fail a run — the same
 * discipline {@code SalaryEnrichmentService} and {@code ResumeMatchService} follow.
 */
@Service
public class LocationClassifier {

    /** Written to {@code logs/ai-scan.log}. Location strings are safe to log; prompts are not. */
    private static final Logger scanLog = LoggerFactory.getLogger("jobdash.ai.scan");
    private static final Logger log = LoggerFactory.getLogger(LocationClassifier.class);

    static final String SCHEMA = """
            {
              "type": "object",
              "required": ["results"],
              "properties": {
                "results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ref", "inUs", "confident"],
                    "properties": {
                      "ref": {"type": "string"},
                      "inUs": {"type": "boolean"},
                      "confident": {"type": "boolean"}
                    }
                  }
                }
              }
            }
            """;

    private final JobListingRepository jobListingRepository;
    private final LocationVerdictRepository locationVerdictRepository;
    private final ClaudeCliClient cliClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LocationClassifier(JobListingRepository jobListingRepository,
                               LocationVerdictRepository locationVerdictRepository,
                               ClaudeCliClient cliClient, AiProperties properties,
                               ObjectMapper objectMapper, Clock clock) {
        this.jobListingRepository = jobListingRepository;
        this.locationVerdictRepository = locationVerdictRepository;
        this.cliClient = cliClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Classifies every distinct location in {@code runId} that is not already cached, then stamps
     * the verdicts — cached and fresh alike — onto that run's rows.
     */
    public ClassifyResult classifyRun(long runId, BooleanSupplier cancelled) {
        try {
            List<String> rawLocations = jobListingRepository.findDistinctLocationsByRun(runId);
            if (rawLocations.isEmpty()) {
                return ClassifyResult.empty();
            }

            // One representative raw string per normalised key: several spellings of the same
            // place must not each consume a slot in a batch.
            Map<String, String> sampleByKey = new LinkedHashMap<>();
            for (String raw : rawLocations) {
                String key = LocationKey.of(raw);
                if (!key.isEmpty()) {
                    sampleByKey.putIfAbsent(key, raw);
                }
            }

            Map<String, LocationVerdict> known = new HashMap<>(
                    locationVerdictRepository.findByKeys(sampleByKey.keySet()));
            List<String> toClassify = sampleByKey.keySet().stream()
                    .filter(k -> !known.containsKey(k))
                    .toList();

            scanLog.info("locations run={} distinct={} cached={} to-classify={}",
                    runId, sampleByKey.size(), known.size(), toClassify.size());

            double costUsd = 0.0;
            String errorMessage = null;
            int classified = 0;

            for (List<String> batch : partition(toClassify, Math.max(1, properties.locationBatchSize()))) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                BatchOutcome outcome = classifyBatch(batch, sampleByKey);
                costUsd += outcome.costUsd();
                if (outcome.errorMessage() != null) {
                    errorMessage = outcome.errorMessage();
                    scanLog.warn("locations batch FAILED ({} string(s)) - {}", batch.size(), errorMessage);
                    // Leave these unclassified; they stay visible and are retried next run.
                    break;
                }
                locationVerdictRepository.upsertAll(outcome.verdicts());
                for (LocationVerdict v : outcome.verdicts()) {
                    known.put(v.locationKey(), v);
                }
                classified += outcome.verdicts().size();
            }

            int hiddenRows = stamp(runId, rawLocations, known);

            scanLog.info("locations DONE run={} classified={} from-cache={} hidden-rows={} cost=${}",
                    runId, classified, sampleByKey.size() - toClassify.size(), hiddenRows,
                    String.format("%.4f", costUsd));

            return new ClassifyResult(sampleByKey.size(), classified,
                    sampleByKey.size() - toClassify.size(), hiddenRows, costUsd, errorMessage);
        } catch (Exception e) {
            log.warn("location classification failed for run {}: {}", runId, e.toString());
            return new ClassifyResult(0, 0, 0, 0, 0.0, "Location classification failed: " + e.getMessage());
        }
    }

    /** Writes each known verdict onto the run's matching rows; returns how many rows are now hidden. */
    private int stamp(long runId, List<String> rawLocations, Map<String, LocationVerdict> known) {
        int hidden = 0;
        for (String raw : rawLocations) {
            LocationVerdict v = known.get(LocationKey.of(raw));
            if (v == null) {
                continue;
            }
            int rows = jobListingRepository.applyLocationVerdict(runId, raw, v.inUs(), v.confident());
            if (!v.inUs() && v.confident()) {
                hidden += rows;
            }
        }
        return hidden;
    }

    private BatchOutcome classifyBatch(List<String> keys, Map<String, String> sampleByKey) {
        // ref is the batch index; verdicts are correlated back by that echoed ref, never by
        // position in the response.
        Map<String, String> keyByRef = new HashMap<>();
        ArrayNode array = objectMapper.createArrayNode();
        for (int i = 0; i < keys.size(); i++) {
            String ref = String.valueOf(i);
            keyByRef.put(ref, keys.get(i));
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ref", ref);
            node.put("location", sampleByKey.get(keys.get(i)));
            array.add(node);
        }

        CliJsonResult result = cliClient.runStructured(prompt(objectMapper.writeValueAsString(array)), SCHEMA);
        return switch (result) {
            case CliJsonResult.Ok ok -> {
                Instant now = clock.instant();
                List<LocationVerdict> verdicts = new ArrayList<>();
                JsonNode results = ok.structuredOutput().get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode r : results) {
                        String key = keyByRef.get(r.path("ref").asString(""));
                        if (key == null) {
                            continue; // a ref matching nothing in this batch - nothing to correlate
                        }
                        verdicts.add(new LocationVerdict(key, sampleByKey.get(key),
                                r.path("inUs").asBoolean(false), r.path("confident").asBoolean(false),
                                properties.model(), now));
                    }
                }
                yield new BatchOutcome(verdicts, ok.costUsd(), null);
            }
            case CliJsonResult.CliNotFound notFound -> new BatchOutcome(List.of(), 0.0, notFound.message());
            case CliJsonResult.Timeout timeout ->
                    new BatchOutcome(List.of(), 0.0, "claude CLI timed out after " + timeout.afterMs() + "ms");
            case CliJsonResult.Failed failed -> new BatchOutcome(List.of(), 0.0, failed.message());
        };
    }

    private static String prompt(String locationsJson) {
        return """
                Classify each job-posting location string below as being in the United States or not.

                These strings are free-form text written by company job boards, in inconsistent
                formats. Some put the country first, some use two-letter codes, some name only a
                city, some name several places at once.

                For each item return:
                  "inUs": true   - the posting is, or can be, based in the United States
                  "inUs": false  - the posting is clearly NOT in the United States
                  "confident": false when the string genuinely does not say where the job is
                                 (a placeholder like "11 Locations", or a bare city name that
                                 exists in several countries) - still give your best inUs guess.

                If a string lists multiple locations and ANY of them is in the United States,
                "inUs" is true.

                Echo each item's "ref" back exactly so the answers can be matched to the inputs.

                LOCATIONS:
                %s
                """.formatted(locationsJson);
    }

    private static List<List<String>> partition(List<String> items, int size) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    private record BatchOutcome(List<LocationVerdict> verdicts, double costUsd, String errorMessage) {
    }

    /**
     * Summary of one classification pass. {@code errorMessage} is non-null when the CLI could not
     * answer — the affected rows stay unclassified and visible, and are retried next run.
     */
    public record ClassifyResult(int distinctLocations, int classified, int fromCache, int hiddenRows,
                                  double costUsd, String errorMessage) {
        public static ClassifyResult empty() {
            return new ClassifyResult(0, 0, 0, 0, 0.0, null);
        }
    }
}
