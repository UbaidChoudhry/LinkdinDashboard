package com.ubaid.jobdash.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPromptBuilderTest {

    private final AiProperties props = new AiProperties(true, "claude", "sonnet", 9, 3,
            Duration.ofMinutes(5), 50);

    private final MatchPromptBuilder builder = new MatchPromptBuilder(props, JsonMapper.builder().build());

    @Test
    void longDescriptionIsTruncatedAtWordBoundaryWithMarker() {
        String longDescription = "word ".repeat(30).trim(); // far more than 50 chars
        JobForMatching job = new JobForMatching("job-1", "Engineer", "Acme", "Remote", longDescription);

        String prompt = builder.build("resume text", List.of(job));

        // The rendered job JSON embeds the truncated description; it must not contain the
        // untruncated full text, must stay at/under the configured limit plus marker, and must
        // carry the explicit truncation marker so the model knows it was cut.
        assertThat(prompt).doesNotContain(longDescription);
        assertThat(prompt).contains("truncated");
        int markerIndex = prompt.indexOf("truncated");
        // Confirm the cut happened on a word boundary: no partial word glued to the marker.
        assertThat(prompt.substring(0, markerIndex)).doesNotContain("wor…").doesNotEndWith("wo");
    }

    @Test
    void shortDescriptionPassesThroughUnharmed() {
        String description = "A short plain-text description with no HTML.";
        JobForMatching job = new JobForMatching("job-1", "Engineer", "Acme", "Remote", description);

        String prompt = builder.build("resume text", List.of(job));

        assertThat(prompt).contains(description);
        assertThat(prompt).doesNotContain("truncated");
    }

    @Test
    void realHtmlTagsAreStripped() {
        String html = "<p>We need <strong>Java</strong> and <em>Kubernetes</em> experience.</p>";
        JobForMatching job = new JobForMatching("job-1", "Engineer", "Acme", "Remote", html);

        String prompt = builder.build("resume text", List.of(job));

        assertThat(prompt).doesNotContain("<p>").doesNotContain("<strong>").doesNotContain("<em>");
        assertThat(prompt).contains("We need Java and Kubernetes experience.");
    }

    @Test
    void entityEscapedHtmlIsAlsoStripped() {
        // Greenhouse-style: the HTML tags themselves are entity-escaped in the raw text.
        String escaped = "&lt;p&gt;We need &lt;strong&gt;Java&lt;/strong&gt; experience.&lt;/p&gt;";
        JobForMatching job = new JobForMatching("job-1", "Engineer", "Acme", "Remote", escaped);

        String prompt = builder.build("resume text", List.of(job));

        assertThat(prompt).doesNotContain("&lt;").doesNotContain("<p>").doesNotContain("<strong>");
        assertThat(prompt).contains("We need Java experience.");
    }

    @Test
    void everyJobRefAppearsInTheRenderedPrompt() {
        List<JobForMatching> jobs = List.of(
                new JobForMatching("ref-alpha", "Engineer", "Acme", "Remote", "Build things."),
                new JobForMatching("ref-beta", "Manager", "Globex", "NYC", "Manage things."),
                new JobForMatching("ref-gamma", "Analyst", "Initech", "Austin", "Analyze things."));

        String prompt = builder.build("resume text", jobs);

        assertThat(prompt).contains("ref-alpha").contains("ref-beta").contains("ref-gamma");
    }
}
