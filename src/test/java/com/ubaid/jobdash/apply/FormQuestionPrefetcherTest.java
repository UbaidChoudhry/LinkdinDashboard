package com.ubaid.jobdash.apply;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link FormQuestionPrefetcher#extract} against saved real-world form HTML - no test
 * here makes a network call; the fixtures under {@code src/test/resources/fixtures/} are trimmed
 * copies of a Greenhouse and a Lever application form fetched once during development.
 */
class FormQuestionPrefetcherTest {

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = FormQuestionPrefetcherTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void greenhouseFixtureYieldsExpectedQuestionsWithSelectSuffixAndNoAttachLine() {
        List<String> lines = FormQuestionPrefetcher.extract(
                loadFixture("greenhouse_embed_stripe.html"), "https://job-boards.greenhouse.io/embed/job_app");

        assertThat(lines).anyMatch(l -> l.contains("First Name"));
        assertThat(lines).anyMatch(l -> l.contains("School"));
        assertThat(lines).anyMatch(l -> l.contains("Degree"));
        assertThat(lines).anyMatch(l -> l.contains("Please select the country where you currently reside")
                && l.contains("[select:"));
        assertThat(lines).noneMatch(l -> l.contains("Attach"));
    }

    @Test
    void greenhouseCountryChecklistDoesNotProduceOnePerCountryLine() {
        List<String> lines = FormQuestionPrefetcher.extract(
                loadFixture("greenhouse_embed_stripe.html"), "https://job-boards.greenhouse.io/embed/job_app");

        assertThat(lines).noneMatch(l -> l.equals("Australia") || l.equals("Belgium") || l.equals("Canada"));
    }

    @Test
    void leverFixtureYieldsExpectedQuestionsWithSelectSuffix() {
        List<String> lines = FormQuestionPrefetcher.extract(
                loadFixture("lever_apply_shieldai.html"), "https://jobs.lever.co/shieldai/x/apply");

        assertThat(lines).anyMatch(l -> l.contains("Full name"));
        assertThat(lines).anyMatch(l -> l.contains("Email"));
        assertThat(lines).anyMatch(l -> l.contains("Gender") && l.contains("[select:"));
        assertThat(lines).noneMatch(l -> l.equals("Yes") || l.equals("No"));
    }

    @Test
    void extractNeverThrowsOnGarbageHtml() {
        List<String> lines = FormQuestionPrefetcher.extract("<not really <html", "https://example.com");
        assertThat(lines).isNotNull();
    }

    @Test
    void extractOnEmptyHtmlReturnsEmptyList() {
        assertThat(FormQuestionPrefetcher.extract("", "https://example.com")).isEmpty();
    }
}
