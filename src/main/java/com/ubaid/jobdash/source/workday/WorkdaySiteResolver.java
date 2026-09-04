package com.ubaid.jobdash.source.workday;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Workday host (e.g. {@code nvidia.wd5.myworkdayjobs.com}) to the site id its jobs
 * API actually needs (e.g. {@code NVIDIAExternalCareerSite}). Public slug catalogs list Workday
 * companies as bare hostnames with no site id, but the API path requires one — guessing
 * conventions had a measured 1-in-4 hit rate.
 * <p>
 * {@code robots.txt} publishes it: {@code Allow: /{site}/} lines. A robots.txt can list several
 * {@code Allow:} lines — every candidate is validated against the real jobs endpoint in order,
 * and the first to answer HTTP 200 wins. A robots.txt can also not be a robots file at all (some
 * hosts, e.g. {@code netflix.wd1.myworkdayjobs.com}, return a JSON error body there) — that
 * simply yields zero {@code Allow:} matches and this resolver returns empty, never throwing.
 * <p>
 * Deliberately independent of {@link com.ubaid.jobdash.source.ats.AtsRateLimiter}: it exists so
 * a company-registry import can resolve and cache a site id once, up front, rather than paying
 * this cost on every run.
 */
@Component
public class WorkdaySiteResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkdaySiteResolver.class);

    // Workday serves robots.txt only to something that looks like a browser on some tenants.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern ALLOW_LINE = Pattern.compile("(?im)^Allow:\\s*/([^/\\s]+)/");

    private final HttpClient httpClient;

    public WorkdaySiteResolver(@Qualifier("atsHttpClient") HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Resolved site id for {@code host}, or empty if it could not be determined. */
    public Optional<String> resolve(String host) {
        String robotsBody;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + "/robots.txt"))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            robotsBody = response.body();
        } catch (IOException | InterruptedException e) {
            log.warn("workday robots.txt fetch failed for {}: {}", host, e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        List<String> candidates = extractAllowSites(robotsBody);
        String tenant = tenantOf(host);
        for (String candidate : candidates) {
            if (validates(host, tenant, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Extracts every {@code Allow: /{site}/} candidate, in the order robots.txt lists them. */
    static List<String> extractAllowSites(String robotsTxt) {
        List<String> sites = new ArrayList<>();
        if (robotsTxt == null) {
            return sites;
        }
        Matcher m = ALLOW_LINE.matcher(robotsTxt);
        while (m.find()) {
            sites.add(m.group(1));
        }
        return sites;
    }

    static String tenantOf(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? host : host.substring(0, dot);
    }

    private boolean validates(String host, String tenant, String site) {
        try {
            String url = "https://" + host + "/wday/cxs/" + tenant + "/" + site + "/jobs";
            String requestBody = "{\"appliedFacets\":{},\"limit\":1,\"offset\":0,\"searchText\":\"\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
