package com.ubaid.jobdash.source.linkedin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link DetailParser} against the three live detail fragments captured on 2026-08-28
 * ({@code frag_*.html}). Per HANDOFF.md §1, every assertion here is on a positive outcome - a
 * description of real length, the right criteria values - never merely "didn't throw".
 */
class DetailParserTest {

    private final DetailParser parser = new DetailParser();

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = DetailParserTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesTheFigmaFragmentCompletely() {
        JobDetail detail = parser.parse(loadFixture("frag_4239977994.html")).orElseThrow();

        assertThat(detail.title()).isEqualTo("Software Engineer - Distributed Systems");
        assertThat(detail.company()).isEqualTo("Figma");
        assertThat(detail.seniorityLevel()).isEqualTo("Mid-Senior level");
        assertThat(detail.employmentType()).isEqualTo("Full-time");
        assertThat(detail.jobFunction()).isEqualTo("Engineering and Information Technology");
        assertThat(detail.industries()).isEqualTo("Design Services");
        assertThat(detail.description())
                .startsWith("Figma is growing our team")
                .hasSizeGreaterThan(1000);
        assertThat(detail.descriptionHash()).matches("[0-9a-f]{32}");
    }

    @Test
    void applyKindIsOffsiteWhenTheSignInModalMarkerIsPresent() {
        for (String name : List.of("frag_4239977994.html", "frag_4372129764.html")) {
            JobDetail detail = parser.parse(loadFixture(name)).orElseThrow();
            assertThat(detail.applyKind()).as(name).isEqualTo("offsite");
        }
    }

    @Test
    void applyKindIsOnsiteWhenTheApplyButtonIsEasyApply() {
        JobDetail detail = parser.parse(loadFixture("frag_4296094481.html")).orElseThrow();
        assertThat(detail.applyKind()).isEqualTo("onsite");
    }

    @Test
    void everyCapturedFragmentYieldsARealDescription() {
        for (String name : List.of("frag_4239977994.html", "frag_4296094481.html", "frag_4372129764.html")) {
            Optional<JobDetail> detail = parser.parse(loadFixture(name));
            assertThat(detail).as(name).isPresent();
            assertThat(detail.get().description()).as(name).hasSizeGreaterThan(500);
            // The sign-in modal boilerplate surrounding the fragment must never leak in.
            assertThat(detail.get().description()).as(name).doesNotContain("Sign in", "Join now");
        }
    }

    @Test
    void lineBreaksAndListItemsSurviveAsNewlines() {
        String html = """
                <div class="show-more-less-html__markup">
                  Intro paragraph.<br><br>Requirements:<ul><li>Java</li><li>SQL</li></ul>
                </div>
                """;
        JobDetail detail = parser.parse(html).orElseThrow();
        assertThat(detail.description()).isEqualTo("Intro paragraph.\n\nRequirements:\n- Java\n- SQL");
    }

    @Test
    void fragmentWithoutADescriptionBlockIsEmptyNotHalfPopulated() {
        assertThat(parser.parse("<html><body><h2 class=\"top-card-layout__title\">X</h2></body></html>")).isEmpty();
        assertThat(parser.parse("<div class=\"show-more-less-html__markup\">   </div>")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void hashIgnoresCaseAndWhitespaceDifferencesOnly() {
        String a = DetailParser.hashOf("Senior  Engineer\n\nRemote");
        String b = DetailParser.hashOf("senior engineer remote");
        String c = DetailParser.hashOf("senior engineer, remote");
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
