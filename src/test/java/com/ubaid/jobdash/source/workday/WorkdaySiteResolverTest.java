package com.ubaid.jobdash.source.workday;

import com.ubaid.jobdash.http.support.StubHttpClient;
import com.ubaid.jobdash.http.support.StubHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkdaySiteResolverTest {

    private StubHttpClient http;
    private WorkdaySiteResolver resolver;

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = WorkdaySiteResolverTest.class.getResourceAsStream(path)) {
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
        resolver = new WorkdaySiteResolver(http);
    }

    @Test
    void extractsSearchFromRealRobotsTxtAndValidatesIt() {
        http.enqueue(new StubHttpResponse(200, loadFixture("workday_robots.txt"), null));
        http.enqueue(new StubHttpResponse(200, "{\"total\":0,\"jobPostings\":[]}", null));

        Optional<String> site = resolver.resolve("3m.wd1.myworkdayjobs.com");

        assertThat(site).contains("Search");
        assertThat(http.requestsSeen()).hasSize(2);
        assertThat(http.requestsSeen().get(1).uri().toString())
                .isEqualTo("https://3m.wd1.myworkdayjobs.com/wday/cxs/3m/Search/jobs");
    }

    @Test
    void jsonInsteadOfRobotsBodyYieldsEmptyWithoutCrashing() {
        http.enqueue(new StubHttpResponse(200, "{\"errorCode\":\"HTTP_422\",\"message\":\"nope\"}", null));

        Optional<String> site = resolver.resolve("netflix.wd1.myworkdayjobs.com");

        assertThat(site).isEmpty();
        // No Allow: lines found, so no validation request should have been attempted.
        assertThat(http.requestsSeen()).hasSize(1);
    }

    @Test
    void triesMultipleAllowLinesInOrderUntilOneValidates() {
        String robots = """
                Sitemap: https://acme.wd1.myworkdayjobs.com/Search/siteMap.xml

                User-agent: *
                Allow: /WrongSite/
                Allow: /RightSite/
                Disallow: /private/
                """;
        http.enqueue(new StubHttpResponse(200, robots, null));
        http.enqueue(new StubHttpResponse(404, "not found", null));
        http.enqueue(new StubHttpResponse(200, "{\"total\":1,\"jobPostings\":[]}", null));

        Optional<String> site = resolver.resolve("acme.wd1.myworkdayjobs.com");

        assertThat(site).contains("RightSite");
        assertThat(http.requestsSeen()).hasSize(3);
    }

    @Test
    void nonOkRobotsStatusYieldsEmpty() {
        http.enqueue(new StubHttpResponse(500, "boom", null));
        assertThat(resolver.resolve("broken.wd1.myworkdayjobs.com")).isEmpty();
    }

    @Test
    void extractAllowSitesParsesRealFixture() {
        List<String> sites = WorkdaySiteResolver.extractAllowSites(loadFixture("workday_robots.txt"));
        assertThat(sites).containsExactly("Search");
    }
}
