package com.ubaid.jobdash.source.greenhouse;

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

class GreenhouseJobSourceTest extends AbstractStoreTest {

    private StubHttpClient http;
    private FakeClock clock;

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = GreenhouseJobSourceTest.class.getResourceAsStream(path)) {
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

    private GreenhouseJobSource source(AtsProperties props) {
        FakeSleeper sleeper = new FakeSleeper(clock);
        AtsRateLimiter limiter = new AtsRateLimiter(clock, sleeper, externalRequestLogRepository,
                props.pacing().minDelay(),
                Map.of("greenhouse", props.dailyCap().greenhouse(), "lever", props.dailyCap().lever(),
                        "workday", props.dailyCap().workday()));
        return new GreenhouseJobSource(http, limiter, props, JsonMapper.builder().build(), clock);
    }

    private static SourceQuery query(String keywords, String location, int hours) {
        return new SourceQuery(keywords, location, hours, "airbnb", "Airbnb", null, null);
    }

    @Test
    void parsesRealFixtureCarryingEntityEscapedContentAndNumericIdAsStringSourceJobId() {
        http.enqueue(new StubHttpResponse(200, loadFixture("greenhouse_jobs.json"), null));

        SourceFetchResult result = source(props()).fetch(query(null, null, 0));

        assertThat(result).isInstanceOf(SourceFetchResult.Ok.class);
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.requestsMade()).isEqualTo(1);
        assertThat(ok.jobs()).hasSize(3);

        SourcedJob first = ok.jobs().stream()
                .filter(j -> j.sourceJobId().equals("8154749"))
                .findFirst().orElseThrow();
        assertThat(first.title()).isEqualTo("Automation Engineer, Quality Engineering");
        assertThat(first.company()).isEqualTo("Airbnb");
        assertThat(first.location()).isEqualTo("Brazil");
        // The entity-escaped content must be carried through verbatim, not decoded.
        assertThat(first.description()).contains("&lt;div class=&quot;content-intro&quot;&gt;");
    }

    @Test
    void filtersLocallyByKeywordAndLocation() {
        http.enqueue(new StubHttpResponse(200, loadFixture("greenhouse_jobs.json"), null));
        SourceFetchResult result = source(props()).fetch(query("Acquisition", null, 0));
        SourceFetchResult.Ok ok = (SourceFetchResult.Ok) result;
        assertThat(ok.jobs()).extracting(SourcedJob::sourceJobId).containsExactly("7995199");

        http.enqueue(new StubHttpResponse(200, loadFixture("greenhouse_jobs.json"), null));
        SourceFetchResult byLocation = source(props()).fetch(query(null, "france", 0));
        SourceFetchResult.Ok okLoc = (SourceFetchResult.Ok) byLocation;
        assertThat(okLoc.jobs()).extracting(SourcedJob::sourceJobId).containsExactly("7995199");
    }

    @Test
    void deadSlugYieldsDeadSlugResultOn404() {
        http.enqueue(new StubHttpResponse(404, loadFixture("greenhouse_dead_slug.json"), null));
        SourceFetchResult result = source(props()).fetch(query(null, null, 0));
        assertThat(result).isInstanceOf(SourceFetchResult.DeadSlug.class);
    }

    @Test
    void dailyCapBlocksBeforeAnyHttpCall() {
        AtsProperties zeroCap = new AtsProperties(true, new AtsProperties.Pacing(Duration.ofMillis(1)), 150,
                new AtsProperties.Workday(120, 20),
                new AtsProperties.DailyCap(0, 2000, 3000),
                2, 33_554_432L,
                new AtsProperties.Slugs("", false));
        SourceFetchResult result = source(zeroCap).fetch(query(null, null, 0));
        assertThat(result).isInstanceOf(SourceFetchResult.RateLimited.class);
        assertThat(http.requestsSeen()).isEmpty();
    }
}
