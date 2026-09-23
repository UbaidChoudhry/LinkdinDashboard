package com.ubaid.jobdash.source.location;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.text.PromptText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether each job can be done fully remotely, from the posting's own text, and stamps the
 * answer onto {@code job_listing.remote}.
 *
 * <p>For LinkedIn the text is the only source (HANDOFF.md §2): its guest cards carry a city, never
 * "Remote", and its remote facet is ignored. Deciding takes two steps:
 * <ol>
 *   <li><b>No CLI call</b> for a posting whose description and location never use remote-work
 *       vocabulary ({@link #REMOTE_WORDS}). A posting cannot say a role is remote without one of
 *       those words, so the row is "not remote". On the data this was built against, that settled
 *       435 of 555 rows for free.</li>
 *   <li>The rest go to Claude in batches of {@code ai.remote-batch-size}. Each job carries only the
 *       excerpts around workplace words ({@link #excerpts}), not its whole description: the question
 *       is narrow and the excerpts are what answer it. Claude is what tells a remote role apart from
 *       "hybrid, 3 days in office", "remote-first culture, based in NYC" or "remote sensing".</li>
 * </ol>
 *
 * <p>A row is decided once; {@code remote} is never re-judged. <b>Never throws.</b> A CLI failure
 * leaves that batch's rows NULL ("not decided yet"), to be retried next time - the same discipline
 * as {@link LocationClassifier}.
 */
@Service
public class RemoteClassifier {

    /** Written to {@code logs/ai-scan.log}, next to the location and match lines. */
    private static final Logger scanLog = LoggerFactory.getLogger("jobdash.ai.scan");
    private static final Logger log = LoggerFactory.getLogger(RemoteClassifier.class);

    static final String NO_MENTION_NOTE = "The posting never mentions remote work.";

    /**
     * A posting that says its role is remote uses at least one of these. Deliberately broad
     * ("remote" also hits "remote sensing"): a false hit only costs a slot in a batch, while a miss
     * would mark a remote job "not remote" with no one checking.
     */
    static final Pattern REMOTE_WORDS = Pattern.compile(
            "remote|work(?:ing)?[\\s-]+from[\\s-]+(?:home|anywhere)|\\bwfh\\b|telecommut|home[\\s-]based",
            Pattern.CASE_INSENSITIVE);

    /** What the excerpts are cut around: the ways a posting says where the work happens. */
    private static final Pattern WORKPLACE_WORDS = Pattern.compile(
            REMOTE_WORDS.pattern()
                    + "|hybrid|on[\\s-]?site|in[\\s-](?:the[\\s-])?office|in[\\s-]person|relocat|commut"
                    + "|days? (?:a|per) week",
            Pattern.CASE_INSENSITIVE);

    /** Characters kept either side of each workplace word. */
    private static final int EXCERPT_RADIUS = 250;
    /** Cap on one job's excerpts, so a posting that says "office" forty times can't crowd out a batch. */
    static final int MAX_EXCERPT_CHARS = 2500;
    private static final int MAX_NOTE_CHARS = 200;

    static final String SCHEMA = """
            {
              "type": "object",
              "required": ["results"],
              "properties": {
                "results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ref", "remote", "note"],
                    "properties": {
                      "ref": {"type": "string"},
                      "remote": {"type": "boolean"},
                      "note": {"type": "string"}
                    }
                  }
                }
              }
            }
            """;

    private final JobListingRepository jobListingRepository;
    private final ClaudeCliClient cliClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public RemoteClassifier(JobListingRepository jobListingRepository, ClaudeCliClient cliClient,
                            AiProperties properties, ObjectMapper objectMapper) {
        this.jobListingRepository = jobListingRepository;
        this.cliClient = cliClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Decides every undecided row in the database ({@link JobListingRepository#findRemoteUndecided}). */
    public ClassifyResult classifyPending(BooleanSupplier cancelled) {
        try {
            if (!properties.enabled()) {
                return ClassifyResult.empty();
            }
            List<JobListing> undecided = jobListingRepository.findRemoteUndecided();
            if (undecided.isEmpty()) {
                return ClassifyResult.empty();
            }

            List<JobListing> candidates = new ArrayList<>();
            int withoutCli = 0;
            for (JobListing job : undecided) {
                if (mentionsRemote(job)) {
                    candidates.add(job);
                } else {
                    jobListingRepository.applyRemote(job.jobId(), false, NO_MENTION_NOTE);
                    withoutCli++;
                }
            }
            scanLog.info("remote undecided={} no-mention={} to-classify={}",
                    undecided.size(), withoutCli, candidates.size());

            double costUsd = 0.0;
            String errorMessage = null;
            int classified = 0;
            int remote = 0;
            for (List<JobListing> batch : partition(candidates, Math.max(1, properties.remoteBatchSize()))) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                BatchOutcome outcome = classifyBatch(batch);
                costUsd += outcome.costUsd();
                if (outcome.errorMessage() != null) {
                    errorMessage = outcome.errorMessage();
                    scanLog.warn("remote batch FAILED ({} job(s)) - {}", batch.size(), errorMessage);
                    // Left NULL; the next run or Re-scan tries them again.
                    break;
                }
                classified += outcome.classified();
                remote += outcome.remote();
            }

            scanLog.info("remote DONE no-mention={} classified={} remote={} cost=${}",
                    withoutCli, classified, remote, String.format("%.4f", costUsd));
            return new ClassifyResult(withoutCli, classified, remote, costUsd, errorMessage);
        } catch (Exception e) {
            log.warn("remote classification failed: {}", e.toString());
            return new ClassifyResult(0, 0, 0, 0.0, "Remote classification failed: " + e.getMessage());
        }
    }

    static boolean mentionsRemote(JobListing job) {
        return REMOTE_WORDS.matcher(nullToEmpty(job.location())).find()
                || REMOTE_WORDS.matcher(PromptText.stripHtml(job.description())).find();
    }

    /**
     * The description's text within {@link #EXCERPT_RADIUS} characters of each workplace word,
     * overlapping windows merged, joined by " … " and capped at {@link #MAX_EXCERPT_CHARS}.
     */
    static String excerpts(String description) {
        String text = PromptText.stripHtml(description);
        StringBuilder out = new StringBuilder();
        Matcher m = WORKPLACE_WORDS.matcher(text);
        int windowStart = -1;
        int windowEnd = -1;
        while (m.find()) {
            int start = Math.max(0, m.start() - EXCERPT_RADIUS);
            int end = Math.min(text.length(), m.end() + EXCERPT_RADIUS);
            if (windowStart >= 0 && start <= windowEnd) {
                windowEnd = Math.max(windowEnd, end);
                continue;
            }
            if (windowStart >= 0 && !appendWindow(out, text, windowStart, windowEnd)) {
                return out.toString();
            }
            windowStart = start;
            windowEnd = end;
        }
        if (windowStart >= 0) {
            appendWindow(out, text, windowStart, windowEnd);
        }
        return out.toString();
    }

    /** Appends one window, cut to fit the cap; false once the cap is reached. */
    private static boolean appendWindow(StringBuilder out, String text, int start, int end) {
        String separator = out.isEmpty() ? "" : " … ";
        int room = MAX_EXCERPT_CHARS - out.length() - separator.length();
        if (room <= 0) {
            return false;
        }
        out.append(separator).append(text, start, Math.min(end, start + room));
        return out.length() < MAX_EXCERPT_CHARS;
    }

    private BatchOutcome classifyBatch(List<JobListing> batch) {
        // ref is the batch index; verdicts are correlated back by that echoed ref, never by
        // position in the response.
        Map<String, JobListing> jobByRef = new HashMap<>();
        ArrayNode array = objectMapper.createArrayNode();
        for (int i = 0; i < batch.size(); i++) {
            String ref = String.valueOf(i);
            JobListing job = batch.get(i);
            jobByRef.put(ref, job);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ref", ref);
            node.put("title", job.title());
            node.put("company", job.company());
            node.put("location", nullToEmpty(job.location()));
            node.put("excerpts", excerpts(job.description()));
            array.add(node);
        }

        CliJsonResult result = cliClient.runStructured(prompt(objectMapper.writeValueAsString(array)), SCHEMA);
        return switch (result) {
            case CliJsonResult.Ok ok -> {
                int classified = 0;
                int remote = 0;
                JsonNode results = ok.structuredOutput().get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode r : results) {
                        JobListing job = jobByRef.remove(r.path("ref").asString(""));
                        if (job == null) {
                            continue; // unknown or repeated ref - nothing to correlate
                        }
                        boolean isRemote = r.path("remote").asBoolean(false);
                        jobListingRepository.applyRemote(job.jobId(), isRemote, note(r.path("note").asString("")));
                        classified++;
                        if (isRemote) {
                            remote++;
                        }
                    }
                }
                yield new BatchOutcome(classified, remote, ok.costUsd(), null);
            }
            case CliJsonResult.CliNotFound notFound -> new BatchOutcome(0, 0, 0.0, notFound.message());
            case CliJsonResult.Timeout timeout ->
                    new BatchOutcome(0, 0, 0.0, "claude CLI timed out after " + timeout.afterMs() + "ms");
            case CliJsonResult.Failed failed -> new BatchOutcome(0, 0, 0.0, failed.message());
        };
    }

    private static String note(String raw) {
        String trimmed = raw.strip();
        return trimmed.length() <= MAX_NOTE_CHARS ? trimmed : trimmed.substring(0, MAX_NOTE_CHARS) + "…";
    }

    private static String prompt(String jobsJson) {
        return """
                For each job posting below, decide whether the job can be done fully remotely.

                You get each posting's title, company, location, and the excerpts of its description
                around every mention of where the work happens - not the whole description.

                For each item return:
                  "remote": true   - the posting says the role itself is remote or can be done from
                                     home: "Remote (US)", "remote-first, work from anywhere in the US",
                                     "this role can be remote or based in our NYC office".
                  "remote": false  - on-site; hybrid (a set number of days in an office); remote only
                                     for occasional days; "remote" in an unrelated sense (remote teams
                                     elsewhere, remote sensing, remote access, remote monitoring); or
                                     nothing in the excerpts says the role itself is remote.
                  "note": the few words from the posting that decided it, at most 15 words,
                          e.g. "Remote - US" or "hybrid, 3 days a week in our Austin office".

                A remote role restricted to a region or country (e.g. "remote within the US") is still
                remote. The location field counts as part of the posting: "Remote - US" there means remote.

                Echo each item's "ref" back exactly so the answers can be matched to the inputs.

                JOBS:
                %s
                """.formatted(jobsJson);
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record BatchOutcome(int classified, int remote, double costUsd, String errorMessage) {
    }

    /**
     * Summary of one pass. {@code withoutCli} rows were settled as "not remote" because the posting
     * never mentions remote work; {@code classified} were answered by Claude, {@code remote} of them
     * remote. {@code errorMessage} is non-null when the CLI could not answer - those rows stay
     * undecided and are retried next time.
     */
    public record ClassifyResult(int withoutCli, int classified, int remote, double costUsd, String errorMessage) {
        public static ClassifyResult empty() {
            return new ClassifyResult(0, 0, 0, 0.0, null);
        }

        public int decided() {
            return withoutCli + classified;
        }
    }
}
