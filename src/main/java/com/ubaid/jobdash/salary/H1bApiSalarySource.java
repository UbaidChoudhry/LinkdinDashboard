package com.ubaid.jobdash.salary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Salary source backed by h1bapi.com's H-1B disclosure salary API. Looks up prevailing/offered
 * wages for an employer + job title (+ optional state) and aggregates them into a median band.
 * <p>
 * NOTE: verified against the public h1bapi.com docs on 2026-09-06 (no key available to test a
 * live response): base {@code https://h1bapi.com/api/v1/salaries}, header auth {@code X-API-Key},
 * GET filters {@code employer}/{@code job_title}/{@code state}, records under
 * {@code data[]} with {@code salary_min}/{@code salary_max}/{@code prevailing_wage_annual}/
 * {@code fiscal_year}. The endpoint URL and field mapping may need adjustment against a live
 * response. Parsing is deliberately defensive.
 * <p>
 * <b>The key is optional.</b> h1bapi.com's free tier is 20 requests/day covering the last two
 * fiscal years; a key raises that ceiling. We call the endpoint either way and only attach the
 * {@code X-API-Key} header when {@code salary.h1b-api.api-key} is set. Because the free
 * allowance is so small, {@code salary.daily-cap.h1bapi} defaults to 20 and this source sits
 * last in the cascade — it is a long-shot fallback, not a primary source.
 */
@Component
public class H1bApiSalarySource implements SalarySource {

    private static final Logger log = LoggerFactory.getLogger(H1bApiSalarySource.class);
    private static final String SOURCE = "h1bapi";
    private static final String ENDPOINT = "https://h1bapi.com/api/v1/salaries";

    private final HttpClient httpClient;
    private final SalaryRateLimiter rateLimiter;
    private final SalaryProperties properties;
    private final ObjectMapper objectMapper;

    public H1bApiSalarySource(@Qualifier("salaryHttpClient") HttpClient httpClient,
                              SalaryRateLimiter rateLimiter,
                              SalaryProperties properties,
                              ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public Optional<SalaryResult> lookup(SalaryLookup q) {
        // A blank key is NOT a disabled source: h1bapi.com's free tier is usable without
        // credentials, so we still make the call and simply omit the auth header. Setting a
        // key only raises the ceiling. (If the service does start demanding a key, the
        // unauthenticated call returns 401 and is handled as any other non-2xx: empty result.)
        String apiKey = properties.h1bApi().apiKey();
        SalaryRateLimiter.Decision quota = rateLimiter.check(SOURCE);
        if (quota != SalaryRateLimiter.Decision.ALLOWED) {
            log.debug("h1bapi salary source skipped: {}", quota);
            return Optional.empty();
        }
        try {
            rateLimiter.pace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        String employer = q.company() == null ? "" : q.company();
        String title = q.title() == null ? "" : q.title();
        String state = UsState.fromLocation(q.location());
        StringBuilder query = new StringBuilder("employer=").append(enc(employer))
                .append("&job_title=").append(enc(title));
        if (!state.isEmpty()) {
            query.append("&state=").append(enc(state));
        }
        // The key travels in the X-API-Key header, so the URL itself is safe to log/record.
        String url = ENDPOINT + "?" + query;

        int status;
        String body;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            body = response.body();
        } catch (java.io.IOException e) {
            log.warn("h1bapi request failed: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        rateLimiter.recordCall(SOURCE, url, status);

        if (status < 200 || status >= 300) {
            log.warn("h1bapi returned HTTP {}", status);
            return Optional.empty();
        }
        try {
            return parse(body);
        } catch (RuntimeException e) {
            log.warn("h1bapi response could not be parsed: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<SalaryResult> parse(String body) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode records = root.get("data");
        if (records == null || !records.isArray()) {
            records = root.get("results");
        }
        if (records == null || !records.isArray()) {
            return Optional.empty();
        }

        List<Double> mins = new ArrayList<>();
        List<Double> maxes = new ArrayList<>();
        int count = 0;
        Integer latestYear = null;
        for (JsonNode r : records) {
            Double lo = num(r, "salary_min");
            Double hi = num(r, "salary_max");
            if (lo == null && hi == null) {
                Double prevailing = num(r, "prevailing_wage_annual");
                lo = prevailing;
                hi = prevailing;
            }
            if (lo != null) {
                mins.add(lo);
            }
            if (hi != null) {
                maxes.add(hi);
            }
            if (lo != null || hi != null) {
                count++;
            }
            Integer fy = intVal(r, "fiscal_year");
            if (fy != null && (latestYear == null || fy > latestYear)) {
                latestYear = fy;
            }
        }

        Double minVal = median(mins);
        Double maxVal = median(maxes);
        if (minVal == null && maxVal == null) {
            return Optional.empty();
        }
        // A US federal fiscal year ends Sept 30; use that as the data point's date.
        LocalDate dataDate = latestYear == null ? null : LocalDate.of(latestYear, 9, 30);
        return Optional.of(new SalaryResult(minVal, maxVal, "USD", dataDate, count, SOURCE));
    }

    private static Double num(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        return n != null && n.isNumber() ? n.doubleValue() : null;
    }

    private static Integer intVal(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.intValue();
        }
        try {
            return n.isString() ? Integer.parseInt(n.asString().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
