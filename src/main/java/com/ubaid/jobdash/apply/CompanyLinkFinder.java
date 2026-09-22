package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.ai.ClaudeCliClient;
import com.ubaid.jobdash.ai.CliJsonResult;
import com.ubaid.jobdash.domain.CompanyLinkMatch;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.CompanyLinkMatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.text.PromptText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Finds each recommended LinkedIn job's posting on the employer's own careers site or ATS, so
 * Apply with Claude can apply there - without anything touching LinkedIn (HANDOFF.md §14; the
 * signed-in LinkedIn route it replaces got its account banned, §13). Claude gets web search and
 * web fetch only - no browser, no LinkedIn fetches - and a batch of jobs (title, company, location,
 * LinkedIn posting date, a description excerpt), and returns for each the posting's URL, a 0-100
 * confidence that it is the same job, which fields agreed, and a one-line note.
 * <p>
 * Every answer is kept in {@code company_link_match}, found or not, so the Results tab can show
 * it and a job is never searched twice. Only a match at or above
 * {@code apply.company-links.min-confidence} becomes the job's {@code apply_url} - so only those
 * reach Apply with Claude - and a link on LinkedIn or a job aggregator, or one that answers 404, is
 * never kept. A batch whose CLI call fails leaves its jobs unsearched, to be retried next time.
 * <p>
 * Runs as a run phase after the AI scan ({@code sweep.RunOrchestrator}) and from the Results tab's
 * Find company links button, one search at a time. Like the scan, it <b>never throws</b>.
 */
@Service
public class CompanyLinkFinder {

    private static final Logger log = LoggerFactory.getLogger(CompanyLinkFinder.class);

    /** Same activity log as Apply with Claude: titles, counts, costs - never descriptions. */
    private static final Logger applyLog = LoggerFactory.getLogger("jobdash.apply");

    static final String SCHEMA = """
            {"type":"object","required":["matches"],"properties":{"matches":{"type":"array","items":{
              "type":"object","required":["jobId","url","confidence","note"],"properties":{
                "jobId":{"type":"string"},"url":{"type":"string"},
                "confidence":{"type":"integer","minimum":0,"maximum":100},
                "matchedOn":{"type":"array","items":{"enum":["title","location","description","requisition","date"]}},
                "note":{"type":"string"}}}}}}
            """;

    /** Description characters per job in the prompt - the 2026-09-23 probe matched on 1500. */
    static final int DESCRIPTION_CHARS = 1500;

    /**
     * A link on one of these hosts (or a subdomain) is never kept: LinkedIn itself, and job
     * aggregators that copy postings rather than host the employer's application.
     */
    private static final List<String> REJECTED_HOSTS = List.of(
            "linkedin.com", "lnkd.in", "indeed.com", "glassdoor.com", "ziprecruiter.com", "simplyhired.com",
            "dice.com", "builtin.com", "wellfound.com", "angel.co", "monster.com", "careerbuilder.com",
            "talent.com", "jooble.org", "lensa.com", "adzuna.com", "jobright.ai", "freehire.me", "dreamworkhq.com");

    /** A URL's HTTP status after redirects, or -1 when it could not be read. Package-visible for tests. */
    @FunctionalInterface
    interface LinkChecker {
        int status(String url);
    }

    /**
     * Where a search stands - running now, or the last one to finish. {@code found} is how many
     * jobs got a link at any confidence; {@code linked} how many of those were confident enough to
     * become the apply link. {@code error} is set when the search could not run at all.
     */
    public record Progress(long runId, boolean running, int total, int done, int found, int linked,
                           double costUsd, String error) {
    }

    private final ClaudeCliClient cliClient;
    private final CompanyLinkProperties properties;
    private final JobListingRepository jobListingRepository;
    private final CompanyLinkMatchRepository matchRepository;
    private final Clock clock;
    private final LinkChecker linkChecker;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Progress> latest = new AtomicReference<>();
    private volatile AtomicBoolean backgroundCancel;

    @Autowired
    public CompanyLinkFinder(ClaudeCliClient cliClient, CompanyLinkProperties properties,
                             JobListingRepository jobListingRepository, CompanyLinkMatchRepository matchRepository,
                             Clock clock) {
        this(cliClient, properties, jobListingRepository, matchRepository, clock, httpLinkChecker());
    }

    /** Test seam: a fake {@link LinkChecker}, so no test makes a network call. */
    CompanyLinkFinder(ClaudeCliClient cliClient, CompanyLinkProperties properties,
                      JobListingRepository jobListingRepository, CompanyLinkMatchRepository matchRepository,
                      Clock clock, LinkChecker linkChecker) {
        this.cliClient = cliClient;
        this.properties = properties;
        this.jobListingRepository = jobListingRepository;
        this.matchRepository = matchRepository;
        this.clock = clock;
        this.linkChecker = linkChecker;
    }

    /** The search running now, or the last one to finish; empty before the first. */
    public Optional<Progress> progress() {
        return Optional.ofNullable(latest.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Searches {@code runId}'s candidates to completion on the caller's thread - the run pipeline's
     * phase. Empty when another search is already running (a button-started one), which then covers
     * the same jobs anyway.
     */
    public Optional<Progress> find(long runId, long resumeId, BooleanSupplier cancelled) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            return Optional.of(search(runId, jobListingRepository.findCompanyLinkCandidates(runId, resumeId), cancelled));
        } catch (RuntimeException e) {
            log.warn("company link search for run {} threw: {}", runId, e.toString());
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }

    /**
     * Starts a search on a virtual thread (the Find company links button) and returns how many
     * jobs it will look for, or empty when a search is already running.
     */
    public OptionalInt startInBackground(long runId, long resumeId) {
        if (!running.compareAndSet(false, true)) {
            return OptionalInt.empty();
        }
        try {
            List<JobListing> candidates = jobListingRepository.findCompanyLinkCandidates(runId, resumeId);
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            backgroundCancel = cancelFlag;
            latest.set(new Progress(runId, true, candidates.size(), 0, 0, 0, 0.0, null));
            Thread.ofVirtual().start(() -> {
                try {
                    search(runId, candidates, cancelFlag::get);
                } finally {
                    backgroundCancel = null;
                    running.set(false);
                }
            });
            return OptionalInt.of(candidates.size());
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    /** Asks a button-started search to stop after its calls in flight. False when none is running. */
    public boolean cancel() {
        AtomicBoolean flag = backgroundCancel;
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    private Progress search(long runId, List<JobListing> candidates, BooleanSupplier cancelled) {
        Tally tally = new Tally(runId, candidates.size());
        latest.set(tally.snapshot(true));
        if (candidates.isEmpty()) {
            Progress done = tally.snapshot(false);
            latest.set(done);
            return done;
        }

        List<List<JobListing>> batches = new ArrayList<>();
        int size = Math.max(1, properties.batchSize());
        for (int from = 0; from < candidates.size(); from += size) {
            batches.add(candidates.subList(from, Math.min(candidates.size(), from + size)));
        }
        int concurrency = Math.max(1, properties.concurrency());
        applyLog.info("company links run {} START jobs={} calls={} at-once={}", runId, candidates.size(),
                batches.size(), concurrency);

        AtomicBoolean fatal = new AtomicBoolean(false);
        Semaphore permits = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < batches.size(); i++) {
                List<JobListing> batch = batches.get(i);
                int number = i + 1;
                executor.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        if (!cancelled.getAsBoolean() && !fatal.get()) {
                            runBatch(runId, number, batches.size(), batch, cancelled, tally, fatal);
                        }
                    } catch (RuntimeException e) {
                        log.warn("company link call {} for run {} threw: {}", number, runId, e.toString());
                    } finally {
                        permits.release();
                    }
                });
            }
        } // close() waits for every call to finish

        Progress done = tally.snapshot(false);
        latest.set(done);
        applyLog.info("company links run {} DONE searched={}/{} found={} linked={} cost=${}{}", runId, done.done(),
                done.total(), done.found(), done.linked(), String.format("%.4f", done.costUsd()),
                cancelled.getAsBoolean() ? " (cancelled)" : done.error() == null ? "" : " error=" + done.error());
        return done;
    }

    private void runBatch(long runId, int number, int of, List<JobListing> batch, BooleanSupplier cancelled,
                          Tally tally, AtomicBoolean fatal) {
        long startedAt = System.nanoTime();
        CliJsonResult result = cliClient.runStructured(buildPrompt(batch), SCHEMA,
                new ClaudeCliClient.CliOptions(cliArgs(properties), properties.timeout(), cancelled));
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

        switch (result) {
            case CliJsonResult.Ok ok -> {
                Map<String, JsonNode> byJobId = new HashMap<>();
                ok.structuredOutput().path("matches").forEach(m -> byJobId.put(m.path("jobId").asString(""), m));
                int found = 0;
                int linked = 0;
                for (JobListing job : batch) {
                    JsonNode entry = byJobId.get(job.sourceJobId());
                    if (entry == null) {
                        continue; // not reported: left unsearched, so the next search tries it again
                    }
                    CompanyLinkMatch match = judge(job, entry);
                    matchRepository.save(match);
                    if (match.found()) {
                        found++;
                        if (match.confidence() >= properties.minConfidence()) {
                            jobListingRepository.setApplyTarget(job.jobId(), match.url(),
                                    ApplyUrlResolver.domainOf(match.url()),
                                    "company site, " + match.confidence() + "% match: " + match.note());
                            linked++;
                        }
                    }
                }
                tally.add(batch.size(), found, linked, ok.costUsd(), null);
                applyLog.info("company links run {} call {}/{} ({} jobs) found={} linked={} cost=${} {}ms", runId,
                        number, of, batch.size(), found, linked, String.format("%.4f", ok.costUsd()), tookMs);
            }
            case CliJsonResult.Failed failed -> {
                tally.add(batch.size(), 0, 0, failed.costUsd(), null);
                applyLog.warn("company links run {} call {}/{} FAILED: {}", runId, number, of, failed.message());
            }
            case CliJsonResult.Timeout timeout -> {
                tally.add(batch.size(), 0, 0, 0.0, null);
                applyLog.warn("company links run {} call {}/{} timed out after {}ms", runId, number, of,
                        timeout.afterMs());
            }
            case CliJsonResult.CliNotFound notFound -> {
                fatal.set(true);
                tally.add(batch.size(), 0, 0, 0.0, notFound.message());
                applyLog.warn("company links run {} stopped: {}", runId, notFound.message());
            }
        }
        latest.set(tally.snapshot(true));
    }

    /**
     * Claude's answer for one job, checked: a link on LinkedIn or an aggregator, or one that
     * answers 404/410, is dropped (the note says why) - Claude's confidence is kept as it said it.
     */
    private CompanyLinkMatch judge(JobListing job, JsonNode entry) {
        String url = entry.path("url").asString("").strip();
        int confidence = Math.clamp(entry.path("confidence").asInt(0), 0, 100);
        List<String> matchedOn = new ArrayList<>();
        entry.path("matchedOn").forEach(n -> matchedOn.add(n.asString("")));
        String note = entry.path("note").asString("").strip();

        String problem = url.isEmpty() ? null : rejectReason(url);
        if (problem == null && !url.isEmpty()) {
            int status = linkChecker.status(url);
            if (status == 404 || status == 410) {
                problem = "the link answered " + status + " (" + url + ")";
            }
        }
        if (problem != null) {
            return new CompanyLinkMatch(job.jobId(), "", confidence, matchedOn, "Dropped: " + problem + ". " + note,
                    clock.instant());
        }
        return new CompanyLinkMatch(job.jobId(), url, confidence, matchedOn, note, clock.instant());
    }

    /** Why a returned URL can't be the employer's posting, or null when it can. */
    static String rejectReason(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null) {
                return "not a web link";
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            for (String rejected : REJECTED_HOSTS) {
                if (host.equals(rejected) || host.endsWith("." + rejected)) {
                    return "not the employer's site (" + host + ")";
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "not a web link";
        }
    }

    /** The prompt for one batch. The model echoes each job's LinkedIn id back as {@code jobId}. */
    static String buildPrompt(List<JobListing> batch) {
        StringBuilder jobs = new StringBuilder();
        for (JobListing job : batch) {
            jobs.append("JOB ").append(job.sourceJobId()).append('\n')
                    .append("TITLE: ").append(nullToEmpty(job.title())).append('\n')
                    .append("COMPANY: ").append(nullToEmpty(job.company())).append('\n')
                    .append("LOCATION: ").append(nullToEmpty(job.location())).append('\n')
                    .append("POSTED ON LINKEDIN: ")
                    .append(job.postedAt() == null ? "unknown" : job.postedAt().toString().substring(0, 10)).append('\n')
                    .append("DESCRIPTION (excerpt): ")
                    .append(PromptText.truncate(PromptText.stripHtml(job.description()), DESCRIPTION_CHARS))
                    .append("\n\n");
        }
        return """
                For each job below, find the SAME job posted on the employer's own careers site or its \
                applicant-tracking system (Greenhouse, Lever, Ashby, Workday, iCIMS, SmartRecruiters, \
                amazon.jobs, etc.), and return the URL of that posting's page - the page a candidate applies from.

                Rules:
                - Use web search to find the employer's careers site and the posting, and fetch the page to \
                confirm it before returning it. Only return a URL you actually fetched or saw in search \
                results - never construct or guess one.
                - Never open or return a linkedin.com URL, and never return a job-aggregator page (Indeed, \
                Glassdoor, ZipRecruiter, SimplyHired, Dice, BuiltIn, Wellfound, etc.) - only the employer's \
                own site or its ATS.
                - Compare the candidate posting to the job: title (exact or near), location, the \
                description's content (responsibilities, requirements, team, product), any requisition/job \
                id, and the posting date (the employer's page may be a few days older than LinkedIn's date).
                - confidence is 0-100: 90+ same requisition id or near-identical description with the same \
                title and location; 70-89 same title and location and clearly the same role; 40-69 plausible \
                but something differs (title variant, other location, several similar openings); below 40 \
                means no real match - then leave url empty.
                - matchedOn lists which of title, location, description, requisition, date agreed. note says \
                in one sentence what you compared and anything that did not agree.
                - Search like this: first the employer's careers site for the exact title; then - always when \
                the employer has many openings with that title - a distinctive phrase from the description \
                in quotes (a team, product or system name, e.g. "Atlas WMS"), which is usually what pins down \
                the exact posting. Pages that 404 are closed postings - keep looking for the live one.
                - Use up to about five searches per job before concluding the employer has no posting for \
                it; then say so and move on. Report every job below, found or not.

                """ + jobs.toString().strip() + "\n";
    }

    /**
     * The CLI flags: web search and fetch only, LinkedIn fetches refused by permission rule as
     * well as by the prompt, no MCP servers (so no browser), no session file. Package-visible for tests.
     */
    static List<String> cliArgs(CompanyLinkProperties p) {
        return List.of(
                "-p",
                "--model", p.model(),
                "--output-format", "json",
                "--json-schema", SCHEMA,
                "--no-session-persistence",
                "--safe-mode",
                "--strict-mcp-config",
                "--tools", "WebSearch", "WebFetch",
                "--allowedTools", "WebSearch", "WebFetch",
                "--disallowedTools", "WebFetch(domain:linkedin.com)", "WebFetch(domain:www.linkedin.com)",
                "--max-turns", String.valueOf(p.maxTurns()),
                "--max-budget-usd", String.valueOf(p.maxBudgetUsd()));
    }

    /** A plain GET (browser user agent, HTTP/1.1, redirects followed) that reports only the status. */
    private static LinkChecker httpLinkChecker() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return url -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/140.0 Safari/537.36")
                        .GET()
                        .build();
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Counts shared by a search's concurrent calls. */
    private static final class Tally {
        private final long runId;
        private final int total;
        private int done;
        private int found;
        private int linked;
        private double costUsd;
        private String error;

        Tally(long runId, int total) {
            this.runId = runId;
            this.total = total;
        }

        synchronized void add(int jobs, int found, int linked, double costUsd, String error) {
            this.done += jobs;
            this.found += found;
            this.linked += linked;
            this.costUsd += costUsd;
            if (error != null) {
                this.error = error;
            }
        }

        synchronized Progress snapshot(boolean running) {
            return new Progress(runId, running, total, done, found, linked, costUsd, error);
        }
    }
}
