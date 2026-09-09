package com.ubaid.jobdash.source.workday;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link JobSource} for Workday's two-phase job-board API. Unlike Greenhouse/Lever, a single
 * request never carries a description or a real date:
 * <ol>
 *     <li>Search: {@code POST https://{host}/wday/cxs/{tenant}/{site}/jobs} — paginated with
 *     {@code offset}/{@code limit}, filtered server-side by {@code searchText} (the only one of
 *     the three ATS sources where the provider does the keyword filtering). Each result carries
 *     only {@code title}/{@code externalPath}/{@code locationsText}/{@code bulletFields} and a
 *     <b>prose</b> {@code postedOn} (literally {@code "Posted 30+ Days Ago"}) — this is never
 *     parsed as a date, but when the run has a recency window it is used as a <em>lower bound
 *     on age</em> to skip obviously stale postings before spending a detail request on them
 *     (see {@link #minimumAgeHours}).</li>
 *     <li>Detail: {@code GET https://{host}/wday/cxs/{tenant}/{site}{externalPath}} — the only
 *     place {@code jobDescription} and the real {@code startDate} (ISO local date) exist. Costs
 *     one extra request per job, so it's capped at {@code ats.workday.max-details-per-run}.</li>
 * </ol>
 * The run's recency window ({@code SourceQuery.hours}) is enforced here on the real date, the
 * same rule Greenhouse and Lever apply through {@link LocalFilter}, at day granularity because
 * {@code startDate} has no time. With a window set, a job that never got a detail request (past
 * the cap, or its detail failed) is dropped rather than kept undated: only a date can prove it
 * is inside the window, and an undated row would silently reintroduce months-old postings.
 * Without a window ({@code hours <= 0}) such jobs are still returned with a null
 * {@code postedAt} and description, as before.
 */
@Component
public class WorkdayJobSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(WorkdayJobSource.class);
    private static final String SOURCE = "workday";

    private final HttpClient httpClient;
    private final AtsRateLimiter rateLimiter;
    private final AtsProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WorkdaySiteResolver siteResolver;

    public WorkdayJobSource(@Qualifier("atsHttpClient") HttpClient httpClient,
                            AtsRateLimiter rateLimiter,
                            AtsProperties properties,
                            ObjectMapper objectMapper,
                            Clock clock,
                            WorkdaySiteResolver siteResolver) {
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.siteResolver = siteResolver;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public SourceFetchResult fetch(SourceQuery q) {
        AtsRateLimiter.Decision quota = rateLimiter.check(SOURCE);
        if (quota != AtsRateLimiter.Decision.ALLOWED) {
            return new SourceFetchResult.RateLimited("workday daily cap reached");
        }

        String host = q.host();
        if (host == null || host.isBlank()) {
            return new SourceFetchResult.TransportError("workday query missing host");
        }

        String site = q.site();
        int requestsMade = 0;
        if (site == null || site.isBlank()) {
            Optional<String> resolved = siteResolver.resolve(host);
            if (resolved.isEmpty()) {
                return new SourceFetchResult.DeadSlug("workday site id could not be resolved for " + host);
            }
            site = resolved.get();
        }
        String tenant = WorkdaySiteResolver.tenantOf(host);

        // Phase 1: paginate the search endpoint. searchText does the keyword filtering
        // server-side; no local keyword filter is applied to Workday results.
        List<Postings> collected = new ArrayList<>();
        int pageSize = Math.max(1, properties.workday().pageSize());
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            try {
                rateLimiter.pace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SourceFetchResult.TransportError("interrupted while pacing");
            }

            String searchUrl = "https://" + host + "/wday/cxs/" + tenant + "/" + site + "/jobs";
            String requestBody = "{\"appliedFacets\":{},\"limit\":" + pageSize + ",\"offset\":" + offset
                    + ",\"searchText\":" + jsonString(q.keywords() == null ? "" : q.keywords()) + "}";
            int status;
            String body;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                body = response.body();
            } catch (IOException e) {
                log.warn("workday search request failed for {}: {}", host, e.toString());
                return new SourceFetchResult.TransportError(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SourceFetchResult.TransportError("interrupted");
            }
            rateLimiter.recordCall(SOURCE, searchUrl, status);
            requestsMade++;

            if (status == 404) {
                return new SourceFetchResult.DeadSlug("workday board not found: " + host + "/" + site);
            }
            if (status < 200 || status >= 300) {
                return new SourceFetchResult.TransportError("workday search returned HTTP " + status);
            }
            if (body != null && body.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes()) {
                return new SourceFetchResult.TransportError("workday response exceeded max-response-bytes");
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (RuntimeException e) {
                return new SourceFetchResult.TransportError("unparseable workday search response");
            }
            JsonNode totalNode = root.get("total");
            total = totalNode != null && totalNode.isNumber() ? totalNode.asInt() : collected.size();

            JsonNode postings = root.get("jobPostings");
            if (postings == null || !postings.isArray() || postings.isEmpty()) {
                break;
            }
            for (JsonNode p : postings) {
                collected.add(toPostings(p));
            }
            offset += pageSize;
        }

        // Phase 2: fetch details up to the run's cap, then enforce the recency window on the real
        // date. The prose postedOn is consulted first so a "Posted 30+ Days Ago" posting never
        // costs a detail request on a 24-hour run.
        int maxDetails = Math.max(0, properties.workday().maxDetailsPerRun());
        boolean windowed = q.hours() > 0;
        List<SourcedJob> jobs = new ArrayList<>();
        int detailsFetched = 0;
        int skippedByProse = 0;
        int droppedByDate = 0;
        int droppedUndated = 0;
        for (Postings p : collected) {
            if (windowed && minimumAgeHours(p.postedOn()) > q.hours()) {
                skippedByProse++;
                continue;
            }
            Instant postedAt = null;
            String description = null;
            if (detailsFetched < maxDetails) {
                try {
                    rateLimiter.pace();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new SourceFetchResult.TransportError("interrupted while pacing");
                }
                DetailResult detail = fetchDetail(host, tenant, site, p.externalPath());
                requestsMade++;
                if (detail == null) {
                    // Detail fetch failed for this one job; keep it, just without enrichment.
                } else {
                    postedAt = detail.postedAt();
                    description = detail.description();
                }
                detailsFetched++;
            }

            if (windowed) {
                if (postedAt == null) {
                    droppedUndated++;
                    continue;
                }
                if (!LocalFilter.postedDayWithinRecency(postedAt, q.hours(), clock)) {
                    droppedByDate++;
                    continue;
                }
            }

            jobs.add(new SourcedJob(
                    SOURCE,
                    p.reqId(),
                    p.title(),
                    q.companyName(),
                    p.locationsText(),
                    postedAt,
                    "https://" + host + "/" + site + p.externalPath(),
                    null,
                    description));
        }

        if (windowed) {
            log.info("workday {}: {} of {} search hits kept within {}h ({} skipped by postedOn prose, "
                            + "{} outside window by startDate, {} undated past the detail cap)",
                    host, jobs.size(), collected.size(), q.hours(), skippedByProse, droppedByDate, droppedUndated);
        }
        return new SourceFetchResult.Ok(jobs, requestsMade);
    }

    private static final Pattern DAYS_AGO = Pattern.compile("posted\\s+(\\d+)(\\+?)\\s+days?\\s+ago");

    /**
     * The least a posting can be old, in hours, given Workday's prose {@code postedOn} - never a
     * date, only a floor. {@code "Posted 30+ Days Ago"} → 30 days. {@code "Posted N Days Ago"} →
     * N-1 days, conservatively, since "3 Days Ago" can mean just over two. {@code "Posted Today"},
     * {@code "Posted Yesterday"}, or anything unrecognised → 0, i.e. never skipped on this alone.
     */
    static long minimumAgeHours(String postedOn) {
        if (postedOn == null) {
            return 0;
        }
        Matcher m = DAYS_AGO.matcher(postedOn.toLowerCase(Locale.ROOT).trim());
        if (!m.matches()) {
            return 0;
        }
        long days = Long.parseLong(m.group(1));
        boolean orMore = !m.group(2).isEmpty();
        long floorDays = orMore ? days : Math.max(0, days - 1);
        return floorDays * 24;
    }

    private DetailResult fetchDetail(String host, String tenant, String site, String externalPath) {
        String detailUrl = "https://" + host + "/wday/cxs/" + tenant + "/" + site + externalPath;
        int status;
        String body;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(detailUrl))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            body = response.body();
        } catch (IOException e) {
            log.warn("workday detail request failed for {}: {}", detailUrl, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        rateLimiter.recordCall(SOURCE, detailUrl, status);

        if (status < 200 || status >= 300) {
            return null;
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode info = root.get("jobPostingInfo");
            if (info == null) {
                return null;
            }
            String description = textOrNull(info, "jobDescription");
            // startDate is the real posted date ("2026-08-02", ISO local date) — NOT postedOn,
            // which is prose ("Posted 30+ Days Ago") and must never be parsed as a date.
            Instant postedAt = parseLocalDate(textOrNull(info, "startDate"));
            return new DetailResult(postedAt, description);
        } catch (RuntimeException e) {
            log.warn("workday detail response for {} could not be parsed: {}", detailUrl, e.toString());
            return null;
        }
    }

    private Postings toPostings(JsonNode p) {
        String title = textOrNull(p, "title");
        String externalPath = textOrNull(p, "externalPath");
        String locationsText = textOrNull(p, "locationsText");
        String postedOn = textOrNull(p, "postedOn");
        String reqId = null;
        JsonNode bulletFields = p.get("bulletFields");
        if (bulletFields != null && bulletFields.isArray() && !bulletFields.isEmpty()) {
            JsonNode first = bulletFields.get(0);
            reqId = first == null ? null : first.asString();
        }
        return new Postings(title, externalPath, locationsText, reqId, postedOn);
    }

    private static Instant parseLocalDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private record Postings(String title, String externalPath, String locationsText, String reqId, String postedOn) {
    }

    private record DetailResult(Instant postedAt, String description) {
    }
}
