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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Salary source backed by the Adzuna "job search" API. Adzuna returns current job postings for
 * a title/location; we aggregate the per-result {@code salary_min}/{@code salary_max} into a
 * median band (falling back to the response's top-level {@code mean}).
 * <p>
 * Disabled (returns empty immediately) when {@code salary.adzuna.app-id}/{@code app-key} are
 * blank. Every outbound call is gated by {@link SalaryRateLimiter} (rolling daily cap + pacing)
 * and recorded. The API key is never included in anything logged or recorded.
 */
@Component
public class AdzunaSalarySource implements SalarySource {

    private static final Logger log = LoggerFactory.getLogger(AdzunaSalarySource.class);
    private static final String SOURCE = "adzuna";
    private static final String ENDPOINT = "https://api.adzuna.com/v1/api/jobs/us/search/1";

    private final HttpClient httpClient;
    private final SalaryRateLimiter rateLimiter;
    private final SalaryProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdzunaSalarySource(@Qualifier("salaryHttpClient") HttpClient httpClient,
                              SalaryRateLimiter rateLimiter,
                              SalaryProperties properties,
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
    public Optional<SalaryResult> lookup(SalaryLookup q) {
        String appId = properties.adzuna().appId();
        String appKey = properties.adzuna().appKey();
        if (isBlank(appId) || isBlank(appKey)) {
            log.debug("adzuna salary source disabled: app-id/app-key not configured");
            return Optional.empty();
        }
        SalaryRateLimiter.Decision quota = rateLimiter.check(SOURCE);
        if (quota != SalaryRateLimiter.Decision.ALLOWED) {
            log.debug("adzuna salary source skipped: {}", quota);
            return Optional.empty();
        }
        try {
            rateLimiter.pace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        String what = q.title() == null ? "" : q.title();
        String where = q.location() == null ? "" : q.location();
        String query = "app_id=" + enc(appId)
                + "&app_key=" + enc(appKey)
                + "&what=" + enc(what)
                + "&where=" + enc(where)
                + "&results_per_page=20&salary_include_unknown=0&content-type=application/json";
        // Logged / recorded form: query params minus the credentials.
        String safeUrl = ENDPOINT + "?what=" + enc(what) + "&where=" + enc(where);

        int status;
        String body;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?" + query))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            body = response.body();
        } catch (java.io.IOException e) {
            log.warn("adzuna request failed: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        rateLimiter.recordCall(SOURCE, safeUrl, status);

        if (status < 200 || status >= 300) {
            log.warn("adzuna returned HTTP {}", status);
            return Optional.empty();
        }
        try {
            return parse(body);
        } catch (RuntimeException e) {
            log.warn("adzuna response could not be parsed: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<SalaryResult> parse(String body) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.get("results");
        List<Double> mins = new ArrayList<>();
        List<Double> maxes = new ArrayList<>();
        int withSalary = 0;
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                JsonNode mn = r.get("salary_min");
                JsonNode mx = r.get("salary_max");
                boolean any = false;
                if (mn != null && mn.isNumber()) {
                    mins.add(mn.doubleValue());
                    any = true;
                }
                if (mx != null && mx.isNumber()) {
                    maxes.add(mx.doubleValue());
                    any = true;
                }
                if (any) {
                    withSalary++;
                }
            }
        }

        Double minVal = median(mins);
        Double maxVal = median(maxes);
        Integer sampleCount = withSalary;

        if (minVal == null && maxVal == null) {
            JsonNode mean = root.get("mean");
            if (mean != null && mean.isNumber()) {
                minVal = mean.doubleValue();
                maxVal = mean.doubleValue();
                sampleCount = null;
            } else {
                return Optional.empty();
            }
        }

        return Optional.of(new SalaryResult(minVal, maxVal, "USD",
                LocalDate.now(clock), sampleCount, SOURCE, null));
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
