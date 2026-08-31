package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdzunaSalarySourceTest extends AbstractStoreTest {

    private static final String APP_KEY = "super-secret-key-value";

    private StubHttpClient http;
    private FakeClock clock;

    private SalaryProperties props(String appId, String appKey) {
        return new SalaryProperties(true, Duration.ofDays(90), Duration.ofDays(1095),
                new SalaryProperties.Pacing(Duration.ofSeconds(1)),
                new SalaryProperties.DailyCap(200, 100),
                new SalaryProperties.MonthlyCap(0, 0),
                new SalaryProperties.Adzuna(appId, appKey),
                new SalaryProperties.H1bApi(""),
                new SalaryProperties.Lca(""));
    }

    private AdzunaSalarySource source(SalaryProperties props) {
        FakeSleeper sleeper = new FakeSleeper(clock);
        SalaryRateLimiter limiter = new SalaryRateLimiter(clock, sleeper, externalRequestLogRepository,
                Duration.ofSeconds(1), Map.of("adzuna", props.dailyCap().adzuna(), "h1bapi", props.dailyCap().h1bapi()),
                Map.of("adzuna", props.monthlyCap().adzuna(), "h1bapi", 0));
        return new AdzunaSalarySource(http, limiter, props, JsonMapper.builder().build(), clock);
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
    void happyPathTakesMedianMinAndMaxAndRecordsTheCall() {
        String json = """
                {"results":[
                  {"salary_min":100000,"salary_max":120000},
                  {"salary_min":140000,"salary_max":160000},
                  {"salary_min":180000,"salary_max":220000}
                ],"mean":150000}
                """;
        http.enqueue(new StubHttpResponse(200, json, null));

        SalaryResult r = source(props("id", APP_KEY)).lookup(lookup()).orElseThrow();

        assertThat(r.salaryMin()).isEqualTo(140000.0);
        assertThat(r.salaryMax()).isEqualTo(160000.0);
        assertThat(r.source()).isEqualTo("adzuna");
        assertThat(r.dataDate()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(r.sampleCount()).isEqualTo(3);

        assertThat(externalRequestLogRepository.countSince("adzuna", Instant.EPOCH)).isEqualTo(1);
        String loggedUrl = client.sql("select url from external_request_log where source = 'adzuna'")
                .query(String.class).single();
        assertThat(loggedUrl).doesNotContain(APP_KEY);
    }

    @Test
    void fallsBackToTopLevelMeanWhenNoPerResultSalary() {
        http.enqueue(new StubHttpResponse(200, "{\"results\":[{}],\"mean\":175000}", null));
        SalaryResult r = source(props("id", APP_KEY)).lookup(lookup()).orElseThrow();
        assertThat(r.salaryMin()).isEqualTo(175000.0);
        assertThat(r.salaryMax()).isEqualTo(175000.0);
    }

    @Test
    void blankKeyShortCircuitsWithoutHittingRateLimiterOrHttp() {
        Optional<SalaryResult> r = source(props("id", "")).lookup(lookup());
        assertThat(r).isEmpty();
        assertThat(http.requestsSeen()).isEmpty();
        assertThat(externalRequestLogRepository.countSince("adzuna", Instant.EPOCH)).isZero();
    }

    @Test
    void dailyCapBlocksBeforeAnyHttpCall() {
        for (int i = 0; i < 200; i++) {
            externalRequestLogRepository.record("adzuna", clock.instant(), "u", 200);
        }
        Optional<SalaryResult> r = source(props("id", APP_KEY)).lookup(lookup());
        assertThat(r).isEmpty();
        assertThat(http.requestsSeen()).isEmpty();
    }

    @Test
    void nonSuccessStatusReturnsEmptyButStillRecords() {
        http.enqueue(new StubHttpResponse(503, "upstream boom", null));
        assertThat(source(props("id", APP_KEY)).lookup(lookup())).isEmpty();
        assertThat(externalRequestLogRepository.countSince("adzuna", Instant.EPOCH)).isEqualTo(1);
    }

    @Test
    void unparseableBodyReturnsEmptyAndNeverThrows() {
        http.enqueue(new StubHttpResponse(200, "<html>not json</html>", null));
        assertThat(source(props("id", APP_KEY)).lookup(lookup())).isEmpty();
    }
}
