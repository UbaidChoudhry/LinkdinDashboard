package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class H1bApiSalarySourceTest extends AbstractStoreTest {

    private static final String API_KEY = "h1b-secret-key";

    private StubHttpClient http;
    private FakeClock clock;

    private SalaryProperties props(String apiKey) {
        return new SalaryProperties(true, Duration.ofDays(90), Duration.ofDays(1095),
                new SalaryProperties.Pacing(Duration.ofSeconds(1)),
                new SalaryProperties.DailyCap(200, 100),
                new SalaryProperties.Adzuna("", ""),
                new SalaryProperties.H1bApi(apiKey),
                new SalaryProperties.Lca(""));
    }

    private H1bApiSalarySource source(SalaryProperties props) {
        FakeSleeper sleeper = new FakeSleeper(clock);
        SalaryRateLimiter limiter = new SalaryRateLimiter(clock, sleeper, externalRequestLogRepository,
                Duration.ofSeconds(1), Map.of("adzuna", 200, "h1bapi", props.dailyCap().h1bapi()));
        return new H1bApiSalarySource(http, limiter, props, JsonMapper.builder().build());
    }

    @BeforeEach
    void setUp() {
        http = new StubHttpClient();
        clock = new FakeClock(Instant.parse("2026-09-06T00:00:00Z"));
    }

    private static SalaryLookup lookup() {
        return new SalaryLookup("Acme Robotics", "Software Engineer", "Austin, Texas",
                "acme robotics", "software engineer");
    }

    @Test
    void happyPathAggregatesRecordsAndSendsKeyInHeaderOnly() {
        String json = """
                {"data":[
                  {"salary_min":150000,"salary_max":170000,"fiscal_year":2024},
                  {"salary_min":170000,"salary_max":190000,"fiscal_year":2025}
                ],"meta":{"page":1}}
                """;
        http.enqueue(new StubHttpResponse(200, json, null));

        SalaryResult r = source(props(API_KEY)).lookup(lookup()).orElseThrow();

        assertThat(r.salaryMin()).isEqualTo(160000.0);
        assertThat(r.salaryMax()).isEqualTo(180000.0);
        assertThat(r.source()).isEqualTo("h1bapi");
        assertThat(r.dataDate()).isEqualTo(LocalDate.of(2025, 9, 30));

        HttpRequest sent = http.requestsSeen().get(0);
        assertThat(sent.headers().firstValue("X-API-Key")).contains(API_KEY);
        assertThat(sent.uri().toString()).doesNotContain(API_KEY);
        assertThat(externalRequestLogRepository.countSince("h1bapi", Instant.EPOCH)).isEqualTo(1);
        String loggedUrl = client.sql("select url from external_request_log where source = 'h1bapi'")
                .query(String.class).single();
        assertThat(loggedUrl).doesNotContain(API_KEY);
    }

    @Test
    void fallsBackToPrevailingWageWhenNoOfferedSalary() {
        http.enqueue(new StubHttpResponse(200,
                "{\"data\":[{\"prevailing_wage_annual\":145000,\"fiscal_year\":2025}]}", null));
        SalaryResult r = source(props(API_KEY)).lookup(lookup()).orElseThrow();
        assertThat(r.salaryMin()).isEqualTo(145000.0);
        assertThat(r.salaryMax()).isEqualTo(145000.0);
    }

    @Test
    void blankKeyShortCircuits() {
        assertThat(source(props("")).lookup(lookup())).isEmpty();
        assertThat(http.requestsSeen()).isEmpty();
        assertThat(externalRequestLogRepository.countSince("h1bapi", Instant.EPOCH)).isZero();
    }

    @Test
    void dailyCapBlocksBeforeHttp() {
        for (int i = 0; i < 100; i++) {
            externalRequestLogRepository.record("h1bapi", clock.instant(), "u", 200);
        }
        assertThat(source(props(API_KEY)).lookup(lookup())).isEmpty();
        assertThat(http.requestsSeen()).isEmpty();
    }

    @Test
    void nonSuccessStatusReturnsEmptyButRecords() {
        http.enqueue(new StubHttpResponse(429, "rate limited", null));
        assertThat(source(props(API_KEY)).lookup(lookup())).isEmpty();
        assertThat(externalRequestLogRepository.countSince("h1bapi", Instant.EPOCH)).isEqualTo(1);
    }

    @Test
    void unparseableBodyReturnsEmpty() {
        http.enqueue(new StubHttpResponse(200, "not json at all", null));
        Optional<SalaryResult> r = source(props(API_KEY)).lookup(lookup());
        assertThat(r).isEmpty();
    }
}
