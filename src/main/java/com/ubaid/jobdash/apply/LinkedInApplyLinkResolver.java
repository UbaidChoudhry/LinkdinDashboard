package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Reads each recommended LinkedIn posting's real external apply link out of the user's Chrome,
 * which must be signed in to a <b>burner</b> LinkedIn account, and stores it on the row
 * ({@code apply_url} / {@code apply_domain} / {@code apply_kind} / {@code apply_match_note}) so
 * "Apply with Claude" can open the company's own site instead of LinkedIn.
 *
 * <p>Why a browser and not a request: LinkedIn's guest pages expose no destination at all (the
 * Apply button is a sign-in modal - HANDOFF.md §13). On a signed-in posting page the button's
 * {@code href} is {@code https://www.linkedin.com/safety/go/?url=<encoded destination>}, which
 * the Claude-in-Chrome CLI reads and URL-decodes with the javascript tool without clicking -
 * verified 2026-09-21 (two postings, 21 turns, $0.30). Easy Apply buttons have no destination.
 *
 * <p>Nothing is ever applied to, clicked through, or changed. Postings are batched
 * {@code apply.linkedin-links.batch-size} per CLI call because the ~25k-token fixed overhead per
 * invocation (§9) dominates the cost. Never throws: every failure becomes a per-row note.
 */
@Service
public class LinkedInApplyLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(LinkedInApplyLinkResolver.class);
    private static final Logger applyLog = LoggerFactory.getLogger("jobdash.apply");
    private static final DateTimeFormatter TRANSCRIPT_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    static final String LINK_JSON_SCHEMA = """
            {"type":"object","required":["loggedIn","results"],"properties":{
              "loggedIn":{"type":"boolean"},
              "results":{"type":"array","items":{"type":"object",
                "required":["jobId","kind","applyUrl","how"],
                "properties":{
                  "jobId":{"type":"string"},
                  "kind":{"enum":["easy_apply","external","closed","error"]},
                  "applyUrl":{"type":"string"},
                  "how":{"type":"string"}}}}}}
            """;

    static final String NOT_SIGNED_IN_NOTE =
            "LinkedIn is not signed in in Chrome - sign in to the burner account and Re-scan";

    public record Summary(int candidates, int external, int easyApply, int closed, int unresolved, int batches,
                          double costUsd, boolean stoppedEarly, String stopReason) {
        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, 0, 0.0, false, null);
        }
    }

    private final ClaudeCliClient cliClient;
    private final ApplyProperties applyProperties;
    private final LinkedInLinkProperties properties;
    private final JobListingRepository jobListingRepository;
    private final AiMatchRepository aiMatchRepository;
    private final Clock clock;

    public LinkedInApplyLinkResolver(ClaudeCliClient cliClient, ApplyProperties applyProperties,
                                     LinkedInLinkProperties properties, JobListingRepository jobListingRepository,
                                     AiMatchRepository aiMatchRepository, Clock clock) {
        this.cliClient = cliClient;
        this.applyProperties = applyProperties;
        this.properties = properties;
        this.jobListingRepository = jobListingRepository;
        this.aiMatchRepository = aiMatchRepository;
        this.clock = clock;
    }

    /** Resolves the run's recommended LinkedIn rows (for {@code resumeId}'s verdicts) that have no link yet. */
    public Summary resolve(long runId, long resumeId, BooleanSupplier cancelled) {
        try {
            return doResolve(runId, resumeId, cancelled);
        } catch (RuntimeException e) {
            log.warn("linkedin link resolution for run {} threw: {}", runId, e.toString());
            return new Summary(0, 0, 0, 0, 0, 0, 0.0, true, "error: " + e.getMessage());
        }
    }

    private Summary doResolve(long runId, long resumeId, BooleanSupplier cancelled) {
        List<JobListing> candidates = candidates(runId, resumeId);
        int total = candidates.size();
        List<JobListing> inScope = candidates.size() > properties.maxPerRun()
                ? candidates.subList(0, properties.maxPerRun()) : candidates;
        for (JobListing over : candidates.subList(inScope.size(), candidates.size())) {
            jobListingRepository.setApplyMatchNote(over.jobId(), "link cap reached - retried next run");
        }

        int external = 0;
        int easyApply = 0;
        int closed = 0;
        int unresolved = 0;
        int batches = 0;
        double costUsd = 0.0;
        boolean stoppedEarly = false;
        String stopReason = null;

        int batchSize = Math.max(1, properties.batchSize());
        for (int from = 0; from < inScope.size(); from += batchSize) {
            if (cancelled.getAsBoolean()) {
                stoppedEarly = true;
                stopReason = "cancelled";
                break;
            }
            List<JobListing> batch = inScope.subList(from, Math.min(inScope.size(), from + batchSize));
            batches++;
            String sessionId = UUID.randomUUID().toString();
            Path transcript = transcriptPath(runId, batches);
            appendTranscript(transcript, "info session " + sessionId + " - " + batch.size() + " postings");

            List<String> args = ApplyOrchestrator.chromeArgs(applyProperties, LINK_JSON_SCHEMA, sessionId,
                    properties.maxTurns(), properties.maxBudgetUsd());
            ClaudeCliClient.StreamOptions options = new ClaudeCliClient.StreamOptions(
                    args, properties.timeout(), applyProperties.idleTimeout(), cancelled);
            CliJsonResult result = cliClient.runStreaming(buildPrompt(batch), LINK_JSON_SCHEMA, options,
                    event -> onEvent(transcript, event));

            switch (result) {
                case CliJsonResult.Ok ok -> {
                    costUsd += ok.costUsd();
                    JsonNode out = ok.structuredOutput();
                    if (!out.path("loggedIn").asBoolean(false)) {
                        for (JobListing job : inScope.subList(from, inScope.size())) {
                            jobListingRepository.setApplyMatchNote(job.jobId(), NOT_SIGNED_IN_NOTE);
                        }
                        unresolved += inScope.size() - from;
                        stoppedEarly = true;
                        stopReason = "not signed in";
                        applyLog.warn("linkedin links run {}: Chrome is not signed in to LinkedIn - phase stopped", runId);
                    } else {
                        Map<String, JsonNode> bySourceId = new HashMap<>();
                        JsonNode results = out.get("results");
                        if (results != null && results.isArray()) {
                            for (JsonNode r : results) {
                                bySourceId.put(r.path("jobId").asString(""), r);
                            }
                        }
                        for (JobListing job : batch) {
                            String outcome = applyResult(job, bySourceId.get(job.sourceJobId()));
                            switch (outcome) {
                                case "external" -> external++;
                                case "easy_apply" -> easyApply++;
                                case "closed" -> closed++;
                                default -> unresolved++;
                            }
                        }
                    }
                }
                case CliJsonResult.CliNotFound notFound -> {
                    noteBatch(batch, "link resolution failed: " + notFound.message());
                    unresolved += batch.size();
                    stoppedEarly = true;
                    stopReason = notFound.message();
                }
                case CliJsonResult.Failed failed -> {
                    costUsd += failed.costUsd();
                    noteBatch(batch, "link resolution failed: " + failed.message());
                    unresolved += batch.size();
                    if (failed.message() != null && failed.message().toLowerCase().contains("extension not connected")) {
                        stoppedEarly = true;
                        stopReason = failed.message();
                    }
                }
                case CliJsonResult.Timeout timeout -> {
                    noteBatch(batch, "link resolution failed: claude CLI timed out after " + timeout.afterMs() + "ms");
                    unresolved += batch.size();
                }
            }
            if (stoppedEarly) {
                int rest = inScope.size() - (from + batch.size());
                if (rest > 0 && !"not signed in".equals(stopReason)) {
                    noteBatch(inScope.subList(from + batch.size(), inScope.size()), "link resolution stopped: " + stopReason);
                    unresolved += rest;
                }
                break;
            }
        }

        Summary summary = new Summary(total, external, easyApply, closed, unresolved, batches, costUsd,
                stoppedEarly, stopReason);
        applyLog.info("linkedin links run {}: candidates={} external={} easy-apply={} closed={} unresolved={} "
                        + "batches={} cost=${}{}", runId, total, external, easyApply, closed, unresolved, batches,
                String.format("%.4f", costUsd), stoppedEarly ? " STOPPED (" + stopReason + ")" : "");
        return summary;
    }

    private List<JobListing> candidates(long runId, long resumeId) {
        List<JobListing> rows = jobListingRepository.findLinkedInUnmatchedByRun(runId);
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, AiMatch> verdicts = aiMatchRepository.findByJobIds(rows.stream().map(JobListing::jobId).toList(),
                resumeId);
        List<JobListing> out = new ArrayList<>();
        for (JobListing row : rows) {
            AiMatch verdict = verdicts.get(row.jobId());
            if (verdict == null || !verdict.recommended()) {
                continue;
            }
            if ("onsite".equals(row.applyKind())) {
                continue; // known Easy Apply - there is no external link to read
            }
            out.add(row);
        }
        return out;
    }

    /** Applies one posting's result to its row; returns the kind that was recorded. */
    private String applyResult(JobListing job, JsonNode r) {
        String label = "job " + job.jobId() + " \"" + job.title() + "\" at \"" + job.company() + "\"";
        if (r == null) {
            jobListingRepository.setApplyMatchNote(job.jobId(), "link not resolved: no result");
            applyLog.info("linkedin link {} -> none [no result]", label);
            return "unresolved";
        }
        String kind = r.path("kind").asString("error");
        String applyUrl = r.path("applyUrl").asString("").trim();
        String how = r.path("how").asString("").trim();
        switch (kind) {
            case "external" -> {
                if (applyUrl.isBlank()) {
                    jobListingRepository.setApplyMatchNote(job.jobId(), "link not resolved: external but no url (" + how + ")");
                    applyLog.info("linkedin link {} -> none [external, no url]", label);
                    return "unresolved";
                }
                String domain = domainOf(applyUrl);
                jobListingRepository.setApplyTarget(job.jobId(), applyUrl, domain, "linkedin apply link (" + how + ")");
                jobListingRepository.setApplyKind(job.jobId(), "offsite");
                applyLog.info("linkedin link {} -> {} {}", label, domain, applyUrl);
                return "external";
            }
            case "easy_apply" -> {
                jobListingRepository.setApplyKind(job.jobId(), "onsite");
                jobListingRepository.setApplyMatchNote(job.jobId(), "Easy Apply - no external link; apply manually");
                applyLog.info("linkedin link {} -> none [Easy Apply]", label);
                return "easy_apply";
            }
            case "closed" -> {
                jobListingRepository.setApplyMatchNote(job.jobId(), "posting closed");
                applyLog.info("linkedin link {} -> none [closed]", label);
                return "closed";
            }
            default -> {
                jobListingRepository.setApplyMatchNote(job.jobId(), "link not resolved: " + (how.isBlank() ? kind : how));
                applyLog.info("linkedin link {} -> none [{}]", label, how.isBlank() ? kind : how);
                return "unresolved";
            }
        }
    }

    private void noteBatch(List<JobListing> jobs, String note) {
        for (JobListing job : jobs) {
            jobListingRepository.setApplyMatchNote(job.jobId(), note);
        }
    }

    /**
     * {@code greenhouse} / {@code lever} / {@code workday} by host, otherwise the host itself
     * without a leading {@code www.} (e.g. {@code jobbol.com.br}); {@code other} when unparseable.
     */
    static String domainOf(String url) {
        if (url == null || url.isBlank()) {
            return "other";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null || host.isBlank()) {
                return "other";
            }
            String h = host.toLowerCase();
            if (h.contains("greenhouse.io")) {
                return "greenhouse";
            }
            if (h.contains("lever.co")) {
                return "lever";
            }
            if (h.contains("myworkdayjobs.com")) {
                return "workday";
            }
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return "other";
        }
    }

    /** The batch prompt. The model echoes each posting's LinkedIn id back as {@code jobId}. */
    static String buildPrompt(List<JobListing> batch) {
        StringBuilder sb = new StringBuilder("""
                You are reading LinkedIn job postings in a Chrome that is signed in to a LinkedIn account.
                Do NOT apply to anything, do NOT click any Easy Apply button, do NOT submit or change
                anything. First open the first posting below in a new tab and set loggedIn to whether the
                page shows a signed-in LinkedIn (a navigation bar with Me / Messaging) rather than a
                sign-in wall; if it is a sign-in wall, stop and return loggedIn=false with an empty
                results array.

                For each posting: open it (reuse one tab, navigating from posting to posting), wait for it
                to load, and read the Apply button's href with the javascript tool. On a signed-in page an
                external apply button links to https://www.linkedin.com/safety/go/?url=<encoded destination>
                - URL-decode the url parameter and report it as applyUrl with kind "external". If the
                button says Easy Apply, report kind "easy_apply" with an empty applyUrl. If the posting
                says it is no longer accepting applications, report "closed". If the href method fails,
                click the Apply button once; if a new tab or a "leaving LinkedIn" page opens, capture the
                final destination URL and then close that tab; never go further than that. Report how you
                got each URL in "how" (href / click+new tab / interstitial). Wait about 3 seconds between
                postings. Close the tab when finished.

                Postings:
                """);
        int n = 1;
        for (JobListing job : batch) {
            sb.append(n++).append(". jobId ").append(job.sourceJobId()).append(": ").append(job.jobUrl()).append('\n');
        }
        return sb.toString();
    }

    private void onEvent(Path transcript, ClaudeCliClient.CliEvent event) {
        appendTranscript(transcript, event.kind() + " " + event.text());
        if ("tool".equals(event.kind()) || "tool_error".equals(event.kind())) {
            applyLog.info("linkedin links {} {}", event.kind(), event.text());
        }
    }

    private Path transcriptPath(long runId, int batch) {
        try {
            Path dir = Path.of(applyProperties.transcriptDir());
            Files.createDirectories(dir);
            return dir.resolve("links-run-" + runId + "-batch-" + batch + ".log");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void appendTranscript(Path transcript, String line) {
        if (transcript == null) {
            return;
        }
        try {
            Files.writeString(transcript, TRANSCRIPT_TIME.format(clock.instant()) + " " + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("could not append to {}: {}", transcript, e.toString());
        }
    }

    /** Test seam: the resolved link for one row, if any. */
    static Optional<String> linkOf(JobListing job) {
        return Optional.ofNullable(job.applyUrl());
    }
}
