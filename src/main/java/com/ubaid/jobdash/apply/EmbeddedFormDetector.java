package com.ubaid.jobdash.apply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the standalone application form behind a company's <i>own</i> careers page.
 * <p>
 * A LinkedIn posting's external apply link often points at the company's site (Stripe's
 * {@code stripe.com/careers/listing/...}) rather than the ATS, and that page renders the real
 * Greenhouse or Lever form inside a cross-origin iframe the Claude-in-Chrome page-reading tools
 * cannot see into - so Claude reaches the form and then can do nothing (HANDOFF.md §12, and
 * batch 9's Stripe job). The page's HTML, though, names the embedded form: an
 * {@code <iframe src="https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=8062305">}
 * (Stripe keeps it in a {@code <noscript>}), or Greenhouse's {@code embed/job_board/js?for=<slug>}
 * loader plus a {@code gh_jid} job id, or a hosted Lever posting URL. One server-side GET recovers
 * that, and the standalone form URL is exactly what {@link ApplyUrlResolver} hands Claude as the
 * DIRECT APPLY FORM URL for a native Greenhouse/Lever row.
 * <p>
 * Best-effort like every other pre-read: {@link #detect} never throws, and any fetch or parse
 * failure yields empty, leaving Claude to open the page and find the form itself as before.
 */
@Component
public class EmbeddedFormDetector {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedFormDetector.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    /** A complete Greenhouse standalone-form URL, with the {@code &} possibly HTML-escaped. */
    private static final Pattern GREENHOUSE_JOB_APP = Pattern.compile(
            "(?:job-boards|boards)\\.greenhouse\\.io/embed/job_app\\?for=([A-Za-z0-9_-]+)&(?:amp;)?token=(\\d+)");

    /** Greenhouse's embedded-board loader ({@code embed/job_board/js?for=slug} or {@code embed/job_board?for=slug}). */
    private static final Pattern GREENHOUSE_BOARD_LOADER = Pattern.compile(
            "(?:job-boards|boards)\\.greenhouse\\.io/embed/job_board(?:/js)?\\?for=([A-Za-z0-9_-]+)");

    /**
     * The job id an embedded Greenhouse board is asked to open: a {@code gh_jid} parameter,
     * {@code Grnhse.Iframe.load(id)}, or a {@code greenhouseId: 8062305} field in a page's own
     * data (Stripe's Next.js state).
     */
    private static final Pattern GREENHOUSE_JOB_ID =
            Pattern.compile("gh_jid=(\\d+)|Iframe\\.load\\(\\s*(\\d+)|greenhouseId\"?\\s*:\\s*\"?(\\d+)");

    /** Greenhouse's public Job Board API - 200 with the posting when a (slug, job id) pair is real, 404 otherwise. */
    static final String BOARDS_API = "https://boards-api.greenhouse.io/v1/boards/";

    /** A run of six or more digits in the page URL's path - the Greenhouse id many career sites put there (Stripe). */
    private static final Pattern URL_PATH_ID = Pattern.compile("/(\\d{6,})(?:[/?#]|$)");

    private static final Pattern LEVER_POSTING = Pattern.compile(
            "https://jobs\\.lever\\.co/([A-Za-z0-9_-]+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final Function<String, Optional<String>> pageFetcher;

    /** Production: fetches the page over HTTP. */
    public EmbeddedFormDetector() {
        // HTTP/1.1 on purpose: over HTTP/2 stripe.com served a variant of its listing page without
        // the noscript Greenhouse iframe that the HTTP/1.1 (curl-equivalent) response carries.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.pageFetcher = url -> fetch(httpClient, url);
    }

    /** Test seam: supplies the page HTML for a URL (or empty for "could not fetch") without any network. */
    EmbeddedFormDetector(Function<String, Optional<String>> pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    /** A detector that never fetches anything - for unit tests of callers that must stay offline. */
    static EmbeddedFormDetector offline() {
        return new EmbeddedFormDetector(url -> Optional.empty());
    }

    /**
     * The standalone form URL embedded in the page at {@code pageUrl}, if it can be established.
     * First the page's own HTML ({@link #detectInHtml}); failing that - the page is bot-walled
     * (zoominfo.com answers 403) or names no form - a guess: the job id from the URL or page
     * ({@code gh_jid}, a 6+ digit path segment, {@code greenhouseId}) plus the site's own name as
     * the board slug ({@code stripe.com} → {@code stripe}), confirmed against Greenhouse's public
     * Job Board API before it is trusted. Never throws.
     */
    public Optional<String> detect(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            String html = pageFetcher.apply(pageUrl).orElse("");
            Optional<String> fromHtml = detectInHtml(html, pageUrl);
            if (fromHtml.isPresent()) {
                return fromHtml;
            }
            return guessGreenhouse(html, pageUrl);
        } catch (RuntimeException e) {
            log.debug("embedded form detection failed for {}: {}", pageUrl, e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> guessGreenhouse(String html, String pageUrl) {
        Optional<String> jobId = greenhouseJobId(html, pageUrl);
        if (jobId.isEmpty()) {
            return Optional.empty();
        }
        Matcher loader = GREENHOUSE_BOARD_LOADER.matcher(html);
        Optional<String> slug = loader.find() ? Optional.of(loader.group(1)) : slugFromHost(pageUrl);
        if (slug.isEmpty()) {
            return Optional.empty();
        }
        String probe = BOARDS_API + slug.get() + "/jobs/" + jobId.get();
        if (pageFetcher.apply(probe).filter(body -> !body.isBlank()).isEmpty()) {
            log.debug("embedded form guess {} for {} not confirmed by the Greenhouse API", probe, pageUrl);
            return Optional.empty();
        }
        return Optional.of(ApplyUrlResolver.greenhouseEmbedUrl(slug.get(), jobId.get()));
    }

    /** {@code https://www.zoominfo.com/careers?...} → {@code zoominfo}: the label before the top-level domain. */
    static Optional<String> slugFromHost(String pageUrl) {
        try {
            String host = URI.create(pageUrl).getHost();
            if (host == null) {
                return Optional.empty();
            }
            String[] labels = host.toLowerCase().split("\\.");
            if (labels.length < 2) {
                return Optional.empty();
            }
            String label = labels[labels.length - 2];
            // "example.co.uk"-style hosts: the label before a two-letter second-level suffix.
            if (label.length() <= 3 && labels.length >= 3 && labels[labels.length - 1].length() == 2) {
                label = labels[labels.length - 3];
            }
            return label.matches("[a-z0-9-]+") ? Optional.of(label) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The pure part: the embedded form URL named by {@code html}, or empty. Package-visible so
     * tests can run it against saved markup with no network. Greenhouse first (a full
     * {@code job_app} URL, then the board loader + a job id from the page or the URL path), then
     * Lever.
     */
    static Optional<String> detectInHtml(String html, String pageUrl) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }
        Matcher jobApp = GREENHOUSE_JOB_APP.matcher(html);
        if (jobApp.find()) {
            return Optional.of(ApplyUrlResolver.greenhouseEmbedUrl(jobApp.group(1), jobApp.group(2)));
        }
        Matcher loader = GREENHOUSE_BOARD_LOADER.matcher(html);
        if (loader.find()) {
            Optional<String> jobId = greenhouseJobId(html, pageUrl);
            if (jobId.isPresent()) {
                return Optional.of(ApplyUrlResolver.greenhouseEmbedUrl(loader.group(1), jobId.get()));
            }
        }
        Matcher lever = LEVER_POSTING.matcher(html);
        if (lever.find()) {
            return Optional.of(ApplyUrlResolver.leverApplyUrl(lever.group()));
        }
        return Optional.empty();
    }

    private static Optional<String> greenhouseJobId(String html, String pageUrl) {
        Matcher inUrl = GREENHOUSE_JOB_ID.matcher(pageUrl == null ? "" : pageUrl);
        if (inUrl.find()) {
            return Optional.of(firstGroup(inUrl));
        }
        Matcher inPage = GREENHOUSE_JOB_ID.matcher(html == null ? "" : html);
        if (inPage.find()) {
            return Optional.of(firstGroup(inPage));
        }
        Matcher pathId = URL_PATH_ID.matcher(pageUrl == null ? "" : pageUrl);
        return pathId.find() ? Optional.of(pathId.group(1)) : Optional.empty();
    }

    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return "";
    }

    private static Optional<String> fetch(HttpClient httpClient, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                log.debug("embedded form detection: {} returned {}", url, response.statusCode());
                return Optional.empty();
            }
            return Optional.of(readCapped(response.body(), MAX_BODY_BYTES));
        } catch (Exception e) {
            log.debug("embedded form detection: could not fetch {}: {}", url, e.toString());
            return Optional.empty();
        }
    }

    private static String readCapped(InputStream in, int maxBytes) throws IOException {
        try (in) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            int total = 0;
            while (total < maxBytes && (read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
