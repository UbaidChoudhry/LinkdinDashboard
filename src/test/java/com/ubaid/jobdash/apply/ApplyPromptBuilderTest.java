package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ApplyPromptBuilder}'s prompt text for both submit modes. */
class ApplyPromptBuilderTest {

    private final ApplyPromptBuilder builder = new ApplyPromptBuilder();

    private JobListing job(String description) {
        return new JobListing(
                1L, "src-1", "lever", "Backend Engineer", "Acme Corp", "Remote",
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"), 1L, "https://acme.com/jobs/1", "https://acme.com",
                FilterVerdict.PASS, 1, null, (UserStatus) null, null, null, null, null, null,
                description, "hash", null, null, null, null, false, null, null);
    }

    private Resume resume() {
        return new Resume(1L, "r1", "resume.pdf", "application/pdf", "resumes/1.pdf",
                "Experienced backend engineer, Java, Kubernetes.", 45, false, Instant.now());
    }

    private ApplicantProfile profile() {
        return new ApplicantProfile("Ada Lovelace", "ada@example.com", "555-1000", "New York, NY",
                "https://linkedin.com/in/ada", "https://ada.dev", "US Citizen", false, "$150,000",
                "Ask me anything else", Instant.now());
    }

    @Test
    void submitFalsePromptForbidsSubmitAndReturnsNeedsReview() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000);

        assertThat(prompt).contains("Do NOT click the final Submit");
        assertThat(prompt).contains("needs_review");
        assertThat(prompt).doesNotContain("SUBMIT ENABLED");
    }

    @Test
    void submitTruePromptContainsSubmitted() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), true, 4000);

        assertThat(prompt).contains("submitted");
        assertThat(prompt).contains("SUBMIT ENABLED");
        assertThat(prompt).doesNotContain("Do NOT click the final Submit");
    }

    @Test
    void promptContainsResumeAbsolutePathAndProfileEmail() {
        Path resumePath = Path.of("/data/resumes/1.pdf");
        String prompt = builder.build(job("plain text description"), resume(), resumePath, profile(), false, 4000);

        assertThat(prompt).contains(resumePath.toAbsolutePath().toString());
        assertThat(prompt).contains("ada@example.com");
    }

    @Test
    void htmlDescriptionIsStripped() {
        String prompt = builder.build(job("<p>Great <b>role</b> at Acme</p>"), resume(),
                Path.of("/data/resumes/1.pdf"), profile(), false, 4000);

        assertThat(prompt).doesNotContain("<p>").doesNotContain("<b>");
        assertThat(prompt).contains("Great role at Acme");
    }

    @Test
    void promptForbidsTheNativeFileDialogAndDescribesTheFailureOutcome() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000);

        assertThat(prompt).contains("NEVER");
        assertThat(prompt).contains("file-picker dialog");
        assertThat(prompt).contains("native file dialog opened");
    }

    @Test
    void promptRequiresAConcreteSummaryBeforeFinishing() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000);

        assertThat(prompt).contains("Before finishing, whatever the outcome, set summary");
    }
}
