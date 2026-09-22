package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the URLs pasted into the Apply tab into {@code job_listing} rows the apply pipeline can
 * drive: source {@code pasted}, the URL as both {@code job_url} and {@code apply_url}, and
 * {@code apply_domain} by host - the same columns a LinkedIn row carries when it has an apply link. {@link ApplyUrlResolver} therefore turns a Greenhouse or Lever link into its iframe-free
 * form and hands a company careers page to {@link EmbeddedFormDetector}, with no pasted-specific
 * logic. A URL pasted twice reuses its row.
 * <p>
 * The title and company are unknown until Claude reads the page, so the row starts with a
 * placeholder title and a company guessed from the URL, and {@link ApplyOrchestrator} replaces both
 * with what Claude reports. {@code last_seen_run_id} is 0 - no sweep run ever saw the row - so the
 * Results tab's run-scoped views never show it, and the filter engine skips the source entirely
 * ({@code JobListingRepository.findWithoutVerdict}). A pasted job Claude submits is marked applied
 * like any other and shows up on the Applied tab.
 */
@Component
public class PastedUrlJobs {

    public static final String SOURCE = "pasted";

    static final String TITLE_PLACEHOLDER = "(title not read yet)";

    /** More URLs than this in one request is almost certainly a paste of the wrong thing. */
    public static final int MAX_URLS = 100;

    private final JobListingRepository jobListingRepository;

    public PastedUrlJobs(JobListingRepository jobListingRepository) {
        this.jobListingRepository = jobListingRepository;
    }

    /**
     * The distinct http(s) URLs among {@code lines}, trimmed, in first-seen order; blank lines are
     * skipped. Throws {@link IllegalArgumentException} with a message naming the first line that is
     * not an absolute http(s) URL, so the user can find it in what they pasted.
     */
    public static List<String> parse(List<String> lines) {
        Set<String> urls = new LinkedHashSet<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String url = line.trim();
            if (!isHttpUrl(url)) {
                throw new IllegalArgumentException("Not a job posting URL: \"" + url + "\". Paste one http(s) URL per line.");
            }
            urls.add(url);
        }
        return List.copyOf(urls);
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Upserts one {@code pasted} row per URL (already {@link #parse parsed}) and returns their job
     * ids in the same order.
     */
    public List<Long> importUrls(List<String> urls, Instant now) {
        List<Long> jobIds = new ArrayList<>();
        for (String url : urls) {
            JobCardInsert card = new JobCardInsert(SOURCE, url, TITLE_PLACEHOLDER, companyGuess(url), null, null,
                    url, null, null);
            jobListingRepository.upsertAll(List.of(card), 0, now);
            long jobId = jobListingRepository.findIdBySourceKey(SOURCE, url).orElseThrow();
            jobListingRepository.setApplyTarget(jobId, url, ApplyUrlResolver.domainOf(url), "pasted URL");
            jobIds.add(jobId);
        }
        return jobIds;
    }

    /**
     * A stand-in company name until Claude reports the real one: the board slug for Greenhouse
     * ({@code for=} or the first path segment), Lever and Ashby, the tenant for Workday
     * ({@code dowjones} from {@code dowjones.wd1.myworkdayjobs.com}, or the segment after
     * {@code /recruiting/} on {@code myworkdaysite.com}), otherwise the host without {@code www.}.
     */
    static String companyGuess(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String[] path = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
        String first = path.length > 1 ? path[1] : "";
        if (host.endsWith("greenhouse.io")) {
            String board = queryParam(uri, "for");
            if (board != null) {
                return board;
            }
            if (!first.isBlank() && !"embed".equals(first)) {
                return first;
            }
        }
        if (("jobs.lever.co".equals(host) || "jobs.ashbyhq.com".equals(host)) && !first.isBlank()) {
            return first;
        }
        if (host.endsWith(".myworkdayjobs.com")) {
            return host.substring(0, host.indexOf('.'));
        }
        if (host.endsWith(".myworkdaysite.com") && path.length > 2 && "recruiting".equals(path[1])) {
            return path[2];
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name) && eq < pair.length() - 1) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
