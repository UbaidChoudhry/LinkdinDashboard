package com.ubaid.jobdash.source.lever;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeverJobSourceTest extends AbstractStoreTest {

    private StubHttpClient http;
    private FakeClock clock;

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = LeverJobSourceTest.class.getResourceAsStream(path)) {
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

    private AtsProperties props() {
        return new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)), 150,
                new AtsProperties.Workday(120, 20),
                new AtsProperties.DailyCap(2000, 2000, 3000),
                2, 33_554_432L,
                new AtsProperties.Slugs("", false));
    }

    private LeverJobSource source(AtsProperties props) {
        FakeSleeper sleeper = new FakeSleeper(clock);
        AtsRateLimiter limiter = new AtsRateLimiter(clock, sleeper, externalRequestLogRepository,
                props.pacing().minDelay(),
                Map.of("greenhouse", props.dailyCap().greenhouse(), "lever", props.dailyCap().lever(),
                        "workday", props.dailyCap().workday()));
        return new LeverJobSource(http, limiter, props, JsonMapper.builder().build(), clock);
    }

    private static SourceQuery query(String keywords, String location, int hours) {
        return new SourceQuery(keywords, location, hours, "palantir", "Palantir", null, null);
    }

    @Test
    void parsesRealFixtureUuidIdAndEpochMillisCreatedAt() {
        http.enqueue(new StubHttpResponse(200, loadFixture("lever_postings.json"), null));

        SourceFetchResult result = source(props()).fetch(query(null, null, 0));

        assertThat(result).isInstanceOf(SourceFetchResult.Ok.class);
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.requestsMade()).isEqualTo(1);
        assertThat(ok.jobs()).hasSize(3);

        SourcedJob job = ok.jobs().stream()
                .filter(j -> j.sourceJobId().equals("ac978161-6f46-4f6b-ad9e-a258e642751c"))
                .findFirst().orElseThrow();
        assertThat(job.title()).isEqualTo("Administrative Business Partner");
        assertThat(job.company()).isEqualTo("Palantir");
        assertThat(job.postedAt()).isEqualTo(Instant.ofEpochMilli(1711403416463L));
    }

    @Test
    void httpOkWithEmptyArrayIsOkWithZeroJobsNotDeadSlug() {
        http.enqueue(new StubHttpResponse(200, "[]", null));

        SourceFetchResult result = source(props()).fetch(query(null, null, 0));

        assertThat(result).isInstanceOf(SourceFetchResult.Ok.class);
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.jobs()).isEmpty();
        assertThat(ok.requestsMade()).isEqualTo(1);
    }

    @Test
    void deadSlugYieldsDeadSlugResultOn404() {
        http.enqueue(new StubHttpResponse(404, loadFixture("lever_dead_slug.json"), null));
        SourceFetchResult result = source(props()).fetch(query(null, null, 0));
        assertThat(result).isInstanceOf(SourceFetchResult.DeadSlug.class);
    }

    @Test
    void filtersLocallyByKeyword() {
        http.enqueue(new StubHttpResponse(200, loadFixture("lever_postings.json"), null));
        SourceFetchResult result = source(props()).fetch(query("Administrative", null, 0));
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.jobs()).extracting(SourcedJob::sourceJobId)
                .containsExactly("ac978161-6f46-4f6b-ad9e-a258e642751c");
    }

    @Test
    void dailyCapBlocksBeforeAnyHttpCall() {
        AtsProperties zeroCap = new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)), 150,
                new AtsProperties.Workday(120, 20),
                new AtsProperties.DailyCap(2000, 0, 3000),
                2, 33_554_432L,
                new AtsProperties.Slugs("", false));
        SourceFetchResult result = source(zeroCap).fetch(query(null, null, 0));
        assertThat(result).isInstanceOf(SourceFetchResult.RateLimited.class);
        assertThat(http.requestsSeen()).isEmpty();
    }
}
