package com.ubaid.jobdash.source.lever;

import com.ubaid.jobdash.source.JobSource;
import com.ubaid.jobdash.source.LocalFilter;
import com.ubaid.jobdash.source.SourceFetchResult;
import com.ubaid.jobdash.source.SourceQuery;
import com.ubaid.jobdash.source.SourcedJob;
import com.ubaid.jobdash.source.ats.AtsProperties;
import com.ubaid.jobdash.source.ats.AtsRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JobSource} for Lever's public postings API:
 * {@code GET https://api.lever.co/v0/postings/{slug}?mode=json}.
 * <p>
 * One request returns the entire board (Palantir: 310 postings / 6MB, Veeva: 900 / 12MB). There
 * is no company-name field in the payload — the caller's {@code companyName} is used. The
 * posting {@code id} is a UUID string, carried through as-is as {@code sourceJobId}.
 * {@code createdAt} is epoch milliseconds.
 * <p>
 * <b>Critical:</b> an alive board with no open jobs returns HTTP 200 with a bare {@code []}.
 * That is NOT a dead slug — {@code kraken}, {@code wealthsimple} and {@code saronic} are all
 * live boards currently returning zero postings — and must yield {@link SourceFetchResult.Ok}
 * with zero jobs, never {@link SourceFetchResult.DeadSlug}. Only an HTTP 404 is a dead slug.
 */
@Component
public class LeverJobSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(LeverJobSource.class);
    private static final String SOURCE = "lever";

    private final HttpClient httpClient;
    private final AtsRateLimiter rateLimiter;
    private final AtsProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LeverJobSource(@Qualifier("atsHttpClient") HttpClient httpClient,
                          AtsRateLimiter rateLimiter,
                          AtsProperties properties,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public SourceFetchResult fetch(SourceQuery q) {
        AtsRateLimiter.Decision quota = rateLimiter.check(SOURCE);
        if (quota != AtsRateLimiter.Decision.ALLOWED) {
            return new SourceFetchResult.RateLimited("lever daily cap reached");
        }
        try {
            rateLimiter.pace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SourceFetchResult.TransportError("interrupted while pacing");
        }

        String url = "https://api.lever.co/v0/postings/" + q.slug() + "?mode=json";
        int status;
        String body;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            body = response.body();
        } catch (IOException e) {
            log.warn("lever request failed for slug {}: {}", q.slug(), e.toString());
            return new SourceFetchResult.TransportError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SourceFetchResult.TransportError("interrupted");
        }
        rateLimiter.recordCall(SOURCE, url, status);

        if (status == 404) {
            return new SourceFetchResult.DeadSlug("lever board not found: " + q.slug());
        }
        if (status < 200 || status >= 300) {
            return new SourceFetchResult.TransportError("lever returned HTTP " + status);
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes()) {
            return new SourceFetchResult.TransportError("lever response exceeded max-response-bytes");
        }

        try {
            List<SourcedJob> jobs = parse(body, q);
            return new SourceFetchResult.Ok(jobs, 1);
        } catch (RuntimeException e) {
            log.warn("lever response for slug {} could not be parsed: {}", q.slug(), e.toString());
            return new SourceFetchResult.TransportError("unparseable lever response");
        }
    }

    private List<SourcedJob> parse(String body, SourceQuery q) {
        // Bare JSON array, not an object — HTTP 200 with "[]" (an alive, empty board) parses to
        // zero postings here just as normally as a populated board. That is deliberate: it is
        // what makes an empty array indistinguishable, at this layer, from "nothing matched".
        JsonNode root = objectMapper.readTree(body);
        List<SourcedJob> result = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return result;
        }
        for (JsonNode p : root) {
            String title = textOrNull(p, "text");
            JsonNode categories = p.get("categories");
            String location = categories == null ? null : textOrNull(categories, "location");
            Instant postedAt = parseEpochMillis(p.get("createdAt"));

            if (!LocalFilter.matchesKeywords(title, q.keywords())) {
                continue;
            }
            if (!LocalFilter.matchesLocation(location, q.location())) {
                continue;
            }
            if (!LocalFilter.withinRecency(postedAt, q.hours(), clock)) {
                continue;
            }

            result.add(new SourcedJob(
                    SOURCE,
                    textOrNull(p, "id"),
                    title,
                    q.companyName(),
                    location,
                    postedAt,
                    textOrNull(p, "hostedUrl"),
                    null,
                    fullDescription(p)));
        }
        return result;
    }

    /**
     * Lever splits a posting into {@code descriptionPlain} (the intro only), {@code lists} (every
     * "Responsibilities" / "Requirements" section, with HTML {@code <li>} content) and
     * {@code additionalPlain} (the closing text). The first field alone is a fraction of the posting:
     * measured 2026-09-21 on Wealthfront's "Backend Engineer", it carried 119 of the 490 words the
     * LinkedIn copy of the same posting had, which made the LinkedIn-to-board matcher score it 0.39
     * while the concatenation scored 1.00 - and the AI scan had been reading that same fragment.
     * This is the text Lever's own posting page renders, in the same order.
     */
    static String fullDescription(JsonNode p) {
        StringBuilder sb = new StringBuilder();
        String intro = textOrNull(p, "descriptionPlain");
        if (intro != null && !intro.isBlank()) {
            sb.append(intro.strip());
        }
        JsonNode lists = p.get("lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode list : lists) {
                String heading = textOrNull(list, "text");
                String content = com.ubaid.jobdash.text.PromptText.stripHtml(textOrNull(list, "content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                if (heading != null && !heading.isBlank()) {
                    sb.append(heading.strip()).append("\n");
                }
                sb.append(content.strip());
            }
        }
        String closing = textOrNull(p, "additionalPlain");
        if (closing != null && !closing.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(closing.strip());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Instant parseEpochMillis(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
