package com.ubaid.jobdash.source.greenhouse;

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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JobSource} for Greenhouse's public guest job board API:
 * {@code GET https://boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true}.
 * <p>
 * One request returns the company's ENTIRE board (Airbnb: 167 jobs / 2MB) — there is no
 * server-side keyword or recency filtering, so both are applied locally here. {@code content}
 * comes back HTML-entity-escaped (the raw string literally contains {@code &lt;div&gt;}); it is
 * carried through as-is, unescaped by nothing in this class, since decoding it is the display
 * layer's concern, not the fetch layer's.
 */
@Component
public class GreenhouseJobSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseJobSource.class);
    private static final String SOURCE = "greenhouse";

    private final HttpClient httpClient;
    private final AtsRateLimiter rateLimiter;
    private final AtsProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GreenhouseJobSource(@Qualifier("atsHttpClient") HttpClient httpClient,
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
            return new SourceFetchResult.RateLimited("greenhouse daily cap reached");
        }
        try {
            rateLimiter.pace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SourceFetchResult.TransportError("interrupted while pacing");
        }

        String url = "https://boards-api.greenhouse.io/v1/boards/" + q.slug() + "/jobs?content=true";
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
            log.warn("greenhouse request failed for slug {}: {}", q.slug(), e.toString());
            return new SourceFetchResult.TransportError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SourceFetchResult.TransportError("interrupted");
        }
        rateLimiter.recordCall(SOURCE, url, status);

        if (status == 404) {
            return new SourceFetchResult.DeadSlug("greenhouse board not found: " + q.slug());
        }
        if (status < 200 || status >= 300) {
            return new SourceFetchResult.TransportError("greenhouse returned HTTP " + status);
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes()) {
            return new SourceFetchResult.TransportError("greenhouse response exceeded max-response-bytes");
        }

        try {
            List<SourcedJob> jobs = parse(body, q);
            return new SourceFetchResult.Ok(jobs, 1);
        } catch (RuntimeException e) {
            log.warn("greenhouse response for slug {} could not be parsed: {}", q.slug(), e.toString());
            return new SourceFetchResult.TransportError("unparseable greenhouse response");
        }
    }

    private List<SourcedJob> parse(String body, SourceQuery q) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode jobsNode = root.get("jobs");
        List<SourcedJob> result = new ArrayList<>();
        if (jobsNode == null || !jobsNode.isArray()) {
            return result;
        }
        for (JsonNode j : jobsNode) {
            String title = textOrNull(j, "title");
            String location = j.has("location") ? textOrNull(j.get("location"), "name") : null;
            Instant postedAt = parseInstant(textOrNull(j, "first_published"));
            if (postedAt == null) {
                postedAt = parseInstant(textOrNull(j, "updated_at"));
            }

            if (!LocalFilter.matchesKeywords(title, q.keywords())) {
                continue;
            }
            if (!LocalFilter.matchesLocation(location, q.location())) {
                continue;
            }
            if (!LocalFilter.withinRecency(postedAt, q.hours(), clock)) {
                continue;
            }

            JsonNode idNode = j.get("id");
            String sourceJobId = idNode == null ? null : String.valueOf(idNode.asLong());
            String companyName = textOrNull(j, "company_name");
            if (companyName == null) {
                companyName = q.companyName();
            }

            result.add(new SourcedJob(
                    SOURCE,
                    sourceJobId,
                    title,
                    companyName,
                    location,
                    postedAt,
                    textOrNull(j, "absolute_url"),
                    null,
                    textOrNull(j, "content")));
        }
        return result;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
