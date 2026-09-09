package com.ubaid.jobdash.source.workday;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import com.ubaid.jobdash.source.SourceFetchResult;
import com.ubaid.jobdash.source.SourceQuery;
import com.ubaid.jobdash.source.SourcedJob;
import com.ubaid.jobdash.source.ats.AtsProperties;
import com.ubaid.jobdash.source.ats.AtsRateLimiter;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkdayJobSourceTest extends AbstractStoreTest {

    private StubHttpClient http;
    private FakeClock clock;

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = WorkdayJobSourceTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() {
        http = new StubHttpClient();
        clock = new FakeClock(Instant.parse("2026-09-07T00:00:00Z"));
    }

    // pageSize large enough that a single search page (offset 0) always satisfies "offset >=
    // total", regardless of the fixture's real total of 1706 - we're testing the mapping, not
    // driving 86 pages of pagination through a stub.
    private AtsProperties props(int maxDetailsPerRun) {
        return new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)), 150,
                new AtsProperties.Workday(maxDetailsPerRun, 2000),
                new AtsProperties.DailyCap(2000, 2000, 3000),
                2, 33_554_432L,
                new AtsProperties.Slugs("", false));
    }

    private WorkdayJobSource source(AtsProperties props) {
        FakeSleeper sleeper = new FakeSleeper(clock);
        AtsRateLimiter limiter = new AtsRateLimiter(clock, sleeper, externalRequestLogRepository,
                props.pacing().minDelay(),
                Map.of("greenhouse", props.dailyCap().greenhouse(), "lever", props.dailyCap().lever(),
                        "workday", props.dailyCap().workday()));
        WorkdaySiteResolver resolver = new WorkdaySiteResolver(http);
        return new WorkdayJobSource(http, limiter, props, JsonMapper.builder().build(), clock, resolver);
    }

    private static SourceQuery query(String host, String site) {
        return query(host, site, 0);
    }

    private static SourceQuery query(String host, String site, int hours) {
        return new SourceQuery("software engineer", null, hours, null, "NVIDIA", host, site);
    }

    private static String detailBody(String startDate) {
        return "{\"jobPostingInfo\":{\"jobDescription\":\"<p>Some role</p>\",\"startDate\":\"" + startDate + "\"}}";
    }

    private static final String HOST = "nvidia.wd5.myworkdayjobs.com";
    private static final String SITE = "NVIDIAExternalCareerSite";

    // --- the recency window ---------------------------------------------------------------
    // The search fixture has three postings: "Posted 30+ Days Ago", "Posted Yesterday",
    // "Posted 30+ Days Ago". The clock is 2026-09-07T00:00Z.

    @Test
    void recencyWindowSkipsProseStalePostingsBeforeSpendingADetailRequest() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_search.json"), null));
        http.enqueue(new StubHttpResponse(200, detailBody("2026-09-06"), null));

        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) source(props(50)).fetch(query(HOST, SITE, 24));

        // Only the "Posted Yesterday" posting was worth a detail request; its real date is
        // yesterday, which is inside a 24h window at day granularity.
        assertThat(ok.jobs()).extracting(SourcedJob::sourceJobId).containsExactly("JR2016510");
        assertThat(ok.jobs().get(0).postedAt())
                .isEqualTo(LocalDate.of(2026, 9, 6).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(http.requestsSeen()).as("1 search page + 1 detail, never a detail for a 30+ day posting").hasSize(2);
        assertThat(ok.requestsMade()).isEqualTo(2);
    }

    @Test
    void recencyWindowDropsAJobWhoseRealDateIsOutsideIt() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_search.json"), null));
        // The prose said "Yesterday" but the detail's startDate says a month ago: the date wins.
        http.enqueue(new StubHttpResponse(200, detailBody("2026-08-02"), null));

        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) source(props(50)).fetch(query(HOST, SITE, 24));

        assertThat(ok.jobs()).isEmpty();
        assertThat(http.requestsSeen()).hasSize(2);
    }

    @Test
    void recencyWindowDropsUndatedJobsPastTheDetailCapInsteadOfKeepingThem() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_search.json"), null));
        http.enqueue(new StubHttpResponse(200, detailBody("2026-08-02"), null));

        // A year-long window so the prose pre-filter skips nothing; a detail cap of 1 so only the
        // first posting is dated. The other two can't prove they're inside the window: dropped.
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) source(props(1)).fetch(query(HOST, SITE, 24 * 365));

        assertThat(ok.jobs()).extracting(SourcedJob::sourceJobId).containsExactly("JR2015623");
        assertThat(http.requestsSeen()).hasSize(2);
    }

    @Test
    void withoutAWindowUndatedJobsPastTheCapAreStillReturned() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_search.json"), null));
        http.enqueue(new StubHttpResponse(200, detailBody("2026-08-02"), null));

        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) source(props(1)).fetch(query(HOST, SITE, 0));

        assertThat(ok.jobs()).hasSize(3);
        assertThat(ok.jobs().get(1).postedAt()).isNull();
    }

    @Test
    void prosePostedOnIsOnlyEverAFloorOnAge() {
        assertThat(WorkdayJobSource.minimumAgeHours("Posted 30+ Days Ago")).isEqualTo(30 * 24);
        assertThat(WorkdayJobSource.minimumAgeHours("Posted 3 Days Ago")).isEqualTo(2 * 24);
        assertThat(WorkdayJobSource.minimumAgeHours("Posted 2 Days Ago")).isEqualTo(24);
        assertThat(WorkdayJobSource.minimumAgeHours("Posted Yesterday")).isZero();
        assertThat(WorkdayJobSource.minimumAgeHours("Posted Today")).isZero();
        assertThat(WorkdayJobSource.minimumAgeHours("something new")).isZero();
        assertThat(WorkdayJobSource.minimumAgeHours(null)).isZero();
    }

    @Test
    void composesDetailUrlUsesRealStartDateAsPostedAtAndNeverParsesProsePostedOn() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_search.json"), null));
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_detail.json"), null));

        SourceFetchResult result = source(props(1))
                .fetch(query("nvidia.wd5.myworkdayjobs.com", "NVIDIAExternalCareerSite"));

        assertThat(result).isInstanceOf(SourceFetchResult.Ok.class);
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.jobs()).hasSize(3);
        assertThat(ok.requestsMade()).isEqualTo(2);

        SourcedJob detailed = ok.jobs().get(0);
        assertThat(detailed.sourceJobId()).isEqualTo("JR2015623");
        assertThat(detailed.jobUrl())
                .isEqualTo("https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite"
                        + "/job/Israel-Yokneam/Software-Engineer--SPE_JR2015623");
        // The real posted date from the detail's startDate, not the prose postedOn.
        assertThat(detailed.postedAt()).isEqualTo(LocalDate.of(2026, 8, 2).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(detailed.description()).contains("NVIDIA");

        // Detail cap of 1: the second and third postings never got a detail request, so they
        // carry a null postedAt and null description rather than a parsed "Posted 30+ Days Ago".
        SourcedJob capped = ok.jobs().get(1);
        assertThat(capped.postedAt()).isNull();
        assertThat(capped.description()).isNull();
        assertThat(capped.title()).isEqualTo("System Software Engineer");

        SourcedJob capped2 = ok.jobs().get(2);
        assertThat(capped2.postedAt()).isNull();
        assertThat(capped2.description()).isNull();

        // Only 2 requests total: 1 search page + 1 detail (the cap), never 3 details.
        assertThat(http.requestsSeen()).hasSize(2);
    }

    @Test
    void unresolvableSiteYieldsDeadSlug() {
        http.enqueue(new StubHttpResponse(200, "{\"errorCode\":\"HTTP_422\"}", null));

        SourceFetchResult result = source(props(50)).fetch(query("netflix.wd1.myworkdayjobs.com", null));

        assertThat(result).isInstanceOf(SourceFetchResult.DeadSlug.class);
    }

    @Test
    void dailyCapBlocksBeforeAnyHttpCall() {
        AtsProperties zeroCap = new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)), 150,
                new AtsProperties.Workday(50, 20),
                new AtsProperties.DailyCap(2000, 2000, 0),
                2, 33_554_432L,
                new AtsProperties.Slugs("", false));
        SourceFetchResult result = source(zeroCap).fetch(query("nvidia.wd5.myworkdayjobs.com", "NVIDIAExternalCareerSite"));
        assertThat(result).isInstanceOf(SourceFetchResult.RateLimited.class);
        assertThat(http.requestsSeen()).isEmpty();
    }
}
