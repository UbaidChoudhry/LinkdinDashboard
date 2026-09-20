package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.ProfileAnswer;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.text.PromptText;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /**
     * Renders the full prompt text sent on stdin for one job's apply attempt, with no direct
     * application-form URL known. Kept for existing call sites; prefer the overload taking
     * {@code directFormUrl} when one may be available.
     */
    public String build(JobListing job, Resume resume, Path resumeAbsolutePath, ApplicantProfile profile,
                         boolean submit, int maxDescriptionChars) {
        return build(job, resume, resumeAbsolutePath, profile, submit, maxDescriptionChars, Optional.empty());
    }


    /**
     * Renders the full prompt text sent on stdin for one job's apply attempt. When
     * {@code directFormUrl} is present, it names a standalone, top-level page that is the
     * application form itself (no iframe) - e.g. Greenhouse's {@code embed/job_app} page or a
     * Lever posting's {@code /apply} page - and the instructions tell Claude to try that first,
     * since a company's own posting page may embed the real form in a cross-origin iframe that
     * the page-reading tools cannot see into.
     */
    public String build(JobListing job, Resume resume, Path resumeAbsolutePath, ApplicantProfile profile,
                         boolean submit, int maxDescriptionChars, Optional<String> directFormUrl) {
        return build(job, resume, resumeAbsolutePath, profile, submit, maxDescriptionChars, directFormUrl,
                List.of(), List.of());
    }

    /**
     * The full prompt, additionally carrying the applicant's saved {@code profile_answer} rows and
     * the server pre-read {@code formQuestions} (see {@link FormQuestionPrefetcher}) - the two
     * pieces that let Claude decide every answer <i>before</i> opening a browser tab, per the
     * plan-first workflow below. {@code answers} splits into a "use these verbatim" list (answered
     * rows) and a "don't ask again, just leave it blank" list (rows still pending from an earlier
     * batch); either section is omitted from the prompt when empty. {@code formQuestions} is
     * merged with the live {@code read_page} result in step 3 below - the live page always wins
     * where they disagree, since it reflects the form exactly as Claude will interact with it.
     */
    public String build(JobListing job, Resume resume, Path resumeAbsolutePath, ApplicantProfile profile,
                         boolean submit, int maxDescriptionChars, Optional<String> directFormUrl,
                         List<ProfileAnswer> answers, List<String> formQuestions) {
        return build(job, resume, resumeAbsolutePath, profile, submit, maxDescriptionChars, job.jobUrl(),
                directFormUrl, answers, formQuestions);
    }

    /**
     * The full prompt, additionally accepting an explicit {@code postingUrl} to render as
     * {@code JOB URL:} instead of {@code job.jobUrl()}. Used for a matched LinkedIn row, where the
     * posting Claude actually opens is the resolved company-site {@code apply_url} - LinkedIn
     * itself is never opened for applying (see {@code ApplyOrchestrator}). Every other overload
     * keeps compiling by delegating here with {@code job.jobUrl()}.
     */
    public String build(JobListing job, Resume resume, Path resumeAbsolutePath, ApplicantProfile profile,
                         boolean submit, int maxDescriptionChars, String postingUrl, Optional<String> directFormUrl,
                         List<ProfileAnswer> answers, List<String> formQuestions) {
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

        String openStep = directFormUrl.isPresent()
                ? """
                  1. If a DIRECT APPLY FORM URL is given, open THAT in a new tab first - it is the \
                  application form as a standalone page with no iframe, so every field and the \
                  resume file input are directly visible to your page-reading tools. Only if it \
                  fails to load or shows no form, fall back to the JOB URL and its Apply link.
                  2. Otherwise, open the job's URL (given below as JOB URL) in a NEW browser tab, then \
                  find and follow the "Apply" link or button on that page to reach the application \
                  form (it may be on the same page or a separate ATS page).\
                  """
                : """
                  1. Open the job's URL (given below as JOB URL) in a NEW browser tab.
                  2. Find and follow the "Apply" link or button on that page to reach the application
                     form (it may be on the same page or a separate ATS page).\
                  """;

        String directFormUrlLine = directFormUrl.map(url -> "\nDIRECT APPLY FORM URL: " + url).orElse("");

        String knownAnswers = knownAnswersSection(answers);
        String pendingQuestions = pendingQuestionsSection(answers);
        String formQuestionsSection = formQuestionsSection(formQuestions);

        return """
                You are applying to a job posting on behalf of a candidate, using the Claude-in-Chrome
                browser extension. Plan the whole form before touching it, then fill it fast: read
                once, decide everything, act in batches. Follow these steps in order:

                %s
                3. Call read_page ONCE, with the interactive-elements filter, to enumerate every field
                   on the form and its element reference. Merge this with FORM QUESTIONS below when
                   given - the two should mostly agree, and the live read_page result is authoritative
                   wherever they don't (the page may have changed, or a field may be conditional).
                4. In ONE message, before filling anything, write out the complete plan: for every
                   field, the exact value you will enter, or "blank (unanswered)". Decide each value
                   in this order - KNOWN ANSWERS below, then the APPLICANT PROFILE, then the RESUME
                   TEXT. A KNOWN ANSWER matches by MEANING, not by exact wording: if a form question
                   asks the same thing as a known one, however it is phrased, use the known answer
                   verbatim. "Opt in to text message updates", "Do you consent to SMS", "Do you opt-in
                   to receive WhatsApp messages" are one consent question; "Are you willing to work a
                   hybrid schedule" and "Can you commit to our hybrid policy" are one question;
                   "{company name}" in a known question stands for the company you are applying to.
                   Only when NO known answer covers the question's meaning does it count as unanswered.
                   Never guess a work-authorization, sponsorship, salary-expectation, or
                   EEO/demographic/personal-preference answer that neither source actually states -
                   leave it blank in the plan and add the question's label text EXACTLY as it appears on
                   the form to the "unanswered" array - no added notes, categories or parentheses
                   (write "Gender", not "Gender (voluntary EEO)"), so repeat questions match the
                   saved answer next time.
                5. Fill the form by element reference, using the form-filling tool, grouping up to 8
                   actions into each browser-batch call. Upload the resume at the absolute path given
                   below (RESUME FILE PATH) using the file-upload tool on the resume <input type=file>
                   element (it may be hidden behind an Attach / Upload / Browse / Choose file button;
                   find the input with the page-reading tools). Do not type or paste resume text into
                   a file upload field - use the actual file. NEVER click a button that opens the
                   operating system's file-picker dialog - that native dialog freezes the page and
                   this run will be killed as stuck. If a native file dialog is already open, stop and
                   set outcome failed with summary "native file dialog opened".
                   Custom dropdowns and autocompletes (School, Degree, country, Yes/No selects,
                   location pickers - anything the form-filling tool cannot set) cost the most time,
                   so handle each one in ONE browser-batch: click the control, type the exact option
                   text (or the first distinctive words of it), wait 1 second, press Enter. Never
                   scroll inside a dropdown list, never type "slowly", and never screenshot to look
                   for an option - if Enter picked the wrong value, read the field back and retry
                   once with a more specific string. Press Enter ONLY while a dropdown's option list is
                   visibly open - Enter on a closed control or a text input submits the whole form (a
                   Greenhouse form was submitted this way with submit disabled). If unsure whether the
                   list is open, click the option instead. Ask read_page for a large max_chars so one
                   call covers the whole form instead of one call per scroll position.
                6. Do one verification pass with read_page (not screenshots) after filling, and fix
                   only the fields that don't match your plan. Do not paste the resume text into a
                   cover-letter or free-text field - if a cover letter is required, write two or three
                   short sentences from the resume and the posting; if it is optional, leave it empty.
                7. Take NO screenshots except one at the very end to confirm the review step - verify
                   values by reading the fields back, not by looking. Narrate nothing between actions;
                   narrate only the plan (step 4) and the final summary. Every extra screenshot or
                   sentence is a full model turn and the biggest cost in this task.

                %s

                The application form may sit inside a cross-origin iframe on the company's own site;
                if find/read_page cannot see the form's fields, do not keep trying coordinates - go to
                the DIRECT APPLY FORM URL (or stop with outcome failed, summary "form in cross-origin
                iframe, no direct URL"), never click Attach/Browse.

                Prefer element references from find/read_page and the form-filling tools over screen
                coordinates; after typing into a field, confirm the value actually appears (zoom or
                read the field) before moving on - typed text has been observed not to register.

                Workday postings (*.myworkdayjobs.com): click Apply, then "Apply Manually". If you are \
                already signed in, continue. Otherwise the tenant shows "Create Account" and/or "Sign In": \
                open Sign In (the "Already have an account?" link if the page opened on Create Account), \
                click the email field ONCE and take one screenshot. If Bitwarden's inline menu is showing \
                under the field, click its entry, then the password field's entry if needed, sign in and \
                continue. If the menu offers to unlock the vault, stop with outcome failed and summary \
                "Bitwarden vault is locked - unlock it and retry". If no menu appears, stop with outcome \
                failed and summary "Workday: no Bitwarden item for <tenant host>". Do not press keyboard \
                shortcuts to summon Bitwarden - keys sent through the browser extension never reach it. \
                Never create an account and never type a password yourself.

                If the job posting is closed, expired, or the page returns a 404 / "not found", stop
                immediately and set "outcome" to "not_found".

                A REQUIRED field you have no answer for is NOT a failure: leave it blank, add its label
                to "unanswered", fill everything else you can, leave the tab open on that step, and set
                "outcome" to "needs_review" - the user answers it in the Resumes tab and the next batch
                uses the answer. Only a blocker no answer could fix - a login wall or account-creation
                gate with no saved login, a CAPTCHA - is "failed"; explain it in "summary".

                Always fill in "summary" with a short, concrete account of what happened (what you
                filled, what stage you reached, and why, if it didn't reach a normal ending).

                Before finishing, whatever the outcome, set summary to a concrete account: which page
                you reached, which fields you filled, which action failed and the exact error text.

                JOB URL: %s%s
                TITLE: %s
                COMPANY: %s
                LOCATION: %s
                DESCRIPTION:
                %s

                RESUME FILE PATH: %s
                %s
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

                %s%s
                RESUME TEXT:
                %s
                """.formatted(
                openStep,
                submitInstruction,
                nullToEmpty(postingUrl),
                directFormUrlLine,
                nullToEmpty(job.title()),
                nullToEmpty(job.company()),
                nullToEmpty(job.location()),
                description,
                resumeAbsolutePath.toAbsolutePath(),
                formQuestionsSection,
                nullToEmpty(profile.fullName()),
                nullToEmpty(profile.email()),
                nullToEmpty(profile.phone()),
                nullToEmpty(profile.location()),
                nullToEmpty(profile.linkedinUrl()),
                nullToEmpty(profile.portfolioUrl()),
                nullToEmpty(profile.workAuthorization()),
                profile.requiresSponsorship() ? "Yes" : "No",
                nullToEmpty(profile.salaryExpectation()),
                knownAnswers,
                pendingQuestions,
                nullToEmpty(resume.contentText()));
    }

    /** {@code KNOWN ANSWERS} section: answered {@code profile_answer} rows, verbatim. Empty string when none. */
    private static String knownAnswersSection(List<ProfileAnswer> answers) {
        List<ProfileAnswer> answered = answers.stream().filter(a -> "answered".equals(a.status())).toList();
        if (answered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "KNOWN ANSWERS (use the answer verbatim for any form question that asks the same thing, "
                        + "even if worded differently):\n");
        for (ProfileAnswer a : answered) {
            sb.append("Q: ").append(a.question()).append("\nA: ").append(a.answer()).append("\n");
        }
        return sb.append("\n").toString();
    }

    /**
     * {@code PREVIOUSLY FLAGGED} section: questions an earlier batch already recorded as
     * unanswered - Claude leaves these blank again rather than re-deciding them, and still lists
     * them in "unanswered" so the count/last-asked tracking in {@code profile_answer} stays
     * accurate. Empty string when none.
     */
    private static String pendingQuestionsSection(List<ProfileAnswer> answers) {
        List<ProfileAnswer> pending = answers.stream().filter(a -> "pending".equals(a.status())).toList();
        if (pending.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "PREVIOUSLY FLAGGED, STILL UNANSWERED (leave blank, list in \"unanswered\" again):\n");
        for (ProfileAnswer a : pending) {
            sb.append("- ").append(a.question()).append("\n");
        }
        return sb.append("\n").toString();
    }

    /** {@code FORM QUESTIONS} section: the server pre-read from {@link FormQuestionPrefetcher}. Empty string when none. */
    private static String formQuestionsSection(List<String> formQuestions) {
        if (formQuestions == null || formQuestions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "\nFORM QUESTIONS (pre-read from the page - the live page is authoritative):\n");
        for (String q : formQuestions) {
            sb.append("- ").append(q).append("\n");
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
