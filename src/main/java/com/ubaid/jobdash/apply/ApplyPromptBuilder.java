package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.text.PromptText;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Renders one job, one resume, one applicant profile, and the submit toggle into the prompt sent
 * to the Claude CLI for "Apply with Claude" ({@code apply.ApplyOrchestrator}), and holds the JSON
 * schema the CLI is told to shape its response into.
 * <p>
 * The prompt tells Claude (driving the Claude-in-Chrome extension) to open the job's posting page
 * in a new tab, follow the Apply link/form, upload the resume file already on disk, and fill the
 * form from the applicant profile first and the resume text second - never inventing an answer
 * neither source provides. Whether the final Submit button may be clicked is controlled entirely
 * by {@code submit}; see {@link #build} for the exact wording of each mode.
 */
@Component
public class ApplyPromptBuilder {

    /**
     * JSON schema for {@code --json-schema}: a required {@code outcome} (one of
     * {@code submitted}/{@code needs_review}/{@code failed}/{@code not_found}) and
     * {@code summary}, plus an optional {@code unanswered} array of questions neither the
     * profile nor the resume could answer.
     */
    public static final String APPLY_JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["outcome", "summary"],
              "properties": {
                "outcome": {"enum": ["submitted", "needs_review", "failed", "not_found"]},
                "summary": {"type": "string"},
                "unanswered": {"type": "array", "items": {"type": "string"}}
              }
            }
            """;

    /** Renders the full prompt text sent on stdin for one job's apply attempt. */
    public String build(JobListing job, Resume resume, Path resumeAbsolutePath, ApplicantProfile profile,
                         boolean submit, int maxDescriptionChars) {
        String description = PromptText.truncate(PromptText.stripHtml(job.description()), maxDescriptionChars);
        String submitInstruction = submit
                ? """
                  This run has SUBMIT ENABLED. After filling and reviewing the form, click the \
                  final Submit / Send application button, wait for the confirmation page to load, \
                  and then set "outcome" to "submitted".\
                  """
                : """
                  This run has SUBMIT DISABLED. Do NOT click the final Submit / Send application \
                  button under any circumstances. Stop at the review step, leave the browser tab \
                  open exactly as it is, and set "outcome" to "needs_review".\
                  """;

        return """
                You are applying to a job posting on behalf of a candidate, using the Claude-in-Chrome
                browser extension. Follow these steps in order:

                1. Open the job's URL (given below as JOB URL) in a NEW browser tab.
                2. Find and follow the "Apply" link or button on that page to reach the application
                   form (it may be on the same page or a separate ATS page).
                3. Locate the resume/CV upload field on the application form and upload the file at
                   the absolute path given below (RESUME FILE PATH). Do not type or paste resume text
                   into a file upload field - use the actual file. Use the browser file-upload tool on
                   the resume <input type=file> element (it may be hidden behind an Attach / Upload /
                   Browse / Choose file button; find the input with the page-reading tools). NEVER
                   click a button that opens the operating system's file-picker dialog - that native
                   dialog freezes the page and this run will be killed as stuck. If a native file
                   dialog is already open, stop and set outcome failed with summary
                   "native file dialog opened".
                4. Fill every other form field. For each field, first check the APPLICANT PROFILE
                   below; if the profile doesn't answer it, check the RESUME TEXT below. If NEITHER
                   the profile nor the resume answers a question, leave that field blank and add the
                   exact question text to the "unanswered" array in your result - do not guess.
                5. Never invent an answer to a work-authorization, sponsorship, salary-expectation, or
                   EEO/demographic question. Only use what the applicant profile or resume actually
                   states; if neither states it, leave it blank and list it in "unanswered".
                6. Do not paste the resume text into a cover-letter or free-text field. If a cover
                   letter is required, write two or three short sentences from the resume and the
                   posting; if it is optional, leave it empty.

                %s

                If the job posting is closed, expired, or the page returns a 404 / "not found", stop
                immediately and set "outcome" to "not_found".

                If you hit a blocker you cannot get past - a login wall, a CAPTCHA, an unavoidable
                required field with no answer available - stop, set "outcome" to "failed", and explain
                the blocker in "summary".

                Always fill in "summary" with a short, concrete account of what happened (what you
                filled, what stage you reached, and why, if it didn't reach a normal ending).

                Before finishing, whatever the outcome, set summary to a concrete account: which page
                you reached, which fields you filled, which action failed and the exact error text.

                JOB URL: %s
                TITLE: %s
                COMPANY: %s
                LOCATION: %s
                DESCRIPTION:
                %s

                RESUME FILE PATH: %s

                APPLICANT PROFILE:
                Full name: %s
                Email: %s
                Phone: %s
                Location: %s
                LinkedIn URL: %s
                Portfolio URL: %s
                Work authorization: %s
                Requires sponsorship: %s
                Salary expectation: %s
                Other notes / how to answer anything else: %s

                RESUME TEXT:
                %s
                """.formatted(
                submitInstruction,
                nullToEmpty(job.jobUrl()),
                nullToEmpty(job.title()),
                nullToEmpty(job.company()),
                nullToEmpty(job.location()),
                description,
                resumeAbsolutePath.toAbsolutePath(),
                nullToEmpty(profile.fullName()),
                nullToEmpty(profile.email()),
                nullToEmpty(profile.phone()),
                nullToEmpty(profile.location()),
                nullToEmpty(profile.linkedinUrl()),
                nullToEmpty(profile.portfolioUrl()),
                nullToEmpty(profile.workAuthorization()),
                profile.requiresSponsorship() ? "Yes" : "No",
                nullToEmpty(profile.salaryExpectation()),
                nullToEmpty(profile.extraAnswers()),
                nullToEmpty(resume.contentText()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
