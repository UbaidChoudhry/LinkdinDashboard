package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.ProfileAnswer;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    @Test
    void directFormUrlPresentAddsUrlLineAndOpenItFirstInstruction() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000,
                Optional.of("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=123"));

        assertThat(prompt).contains(
                "DIRECT APPLY FORM URL: https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=123");
        assertThat(prompt).contains("If a DIRECT APPLY FORM URL is given, open THAT in a new tab first");
        assertThat(prompt).contains("Only if it fails to load or shows no form, fall back to the JOB URL");
    }

    @Test
    void directFormUrlAbsentOmitsUrlLineAndKeepsTodaysWording() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000, Optional.empty());

        assertThat(prompt).doesNotContain("DIRECT APPLY FORM URL:");
        assertThat(prompt).doesNotContain("If a DIRECT APPLY FORM URL is given");
        assertThat(prompt).contains("1. Open the job's URL (given below as JOB URL) in a NEW browser tab.");
    }

    @Test
    void promptWarnsAboutCrossOriginIframesAndForbidsCoordinateGuessing() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000);

        assertThat(prompt).contains("cross-origin iframe");
        assertThat(prompt).contains("never click Attach/Browse");
        assertThat(prompt).contains("Prefer element references from find/read_page and the form-filling tools");
        assertThat(prompt).contains("typed text has been observed not to register");
    }

    @Test
    void promptIncludesWorkdayAccountRequiredRule() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000);

        assertThat(prompt).contains("Workday postings: click Apply, then \"Apply Manually\"");
        assertThat(prompt).contains("Workday account required");
    }

    @Test
    void promptDescribesThePlanFirstWorkflow() {
        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000, Optional.empty(), List.of(), List.of());

        assertThat(prompt).contains("Call read_page ONCE");
        assertThat(prompt).contains("In ONE message, before filling anything, write out the complete plan");
        assertThat(prompt).contains("grouping up to 8");
        assertThat(prompt).contains("browser-batch call");
        assertThat(prompt).contains("Do one verification pass with read_page (not screenshots)");
        assertThat(prompt).contains("Narrate nothing between actions");
        assertThat(prompt).contains("handle each one in ONE browser-batch: click the control, type the exact option");
        assertThat(prompt).contains("Take NO screenshots except one at the very end");
    }

    @Test
    void knownAnswersRenderAndPendingQuestionsRenderSeparately() {
        List<ProfileAnswer> answers = List.of(
                new ProfileAnswer(1, "Portfolio link?", "https://ada.dev", "answered", 0, null, "", Instant.now(),
                        Instant.now()),
                new ProfileAnswer(2, "Desired start date?", "", "pending", 2, 5L, "Acme", Instant.now(),
                        Instant.now()));

        String prompt = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000, Optional.empty(), answers, List.of());

        assertThat(prompt).contains("KNOWN ANSWERS (use these verbatim when a form question matches):");
        assertThat(prompt).contains("Q: Portfolio link?");
        assertThat(prompt).contains("A: https://ada.dev");
        assertThat(prompt).contains("PREVIOUSLY FLAGGED, STILL UNANSWERED (leave blank, list in \"unanswered\" again):");
        assertThat(prompt).contains("Desired start date?");
    }

    @Test
    void formQuestionsSectionRendersWhenPresentAndIsAbsentWhenEmpty() {
        String withQuestions = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000, Optional.empty(), List.of(), List.of("First Name [required]", "School"));

        assertThat(withQuestions).contains("FORM QUESTIONS (pre-read from the page - the live page is authoritative):");
        assertThat(withQuestions).contains("First Name [required]");
        assertThat(withQuestions).contains("School");

        String withoutQuestions = builder.build(job("<p>Great role</p>"), resume(), Path.of("/data/resumes/1.pdf"),
                profile(), false, 4000, Optional.empty(), List.of(), List.of());

        assertThat(withoutQuestions).doesNotContain(
                "FORM QUESTIONS (pre-read from the page - the live page is authoritative):");
    }
}
