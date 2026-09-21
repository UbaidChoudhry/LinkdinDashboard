package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a job listing's ATS posting to a direct, top-level application-form URL when one is
 * known to exist as a standalone page - bypassing a company's own posting page, which may embed
 * the actual form in a cross-origin iframe that the Claude-in-Chrome extension's page-reading
 * tools cannot see into (diagnosed against Stripe's Greenhouse-backed posting). See
 * {@code HANDOFF.md} §12 ("Apply with Claude") for the full story.
 * <p>
 * Greenhouse serves every board's form as a standalone top-level page at
 * {@code https://job-boards.greenhouse.io/embed/job_app?for=<boardSlug>&token=<sourceJobId>}.
 * Lever serves one at {@code <jobUrl>/apply}. Workday has no equivalent, so it resolves to empty.
 * A LinkedIn row resolved to a company's own careers page (Stripe) is handed to
 * {@link EmbeddedFormDetector}, which reads the Greenhouse/Lever form that page embeds. A
 * {@link PastedUrlJobs pasted} URL is treated exactly like such a resolved link.
 */
@Component
public class ApplyUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(ApplyUrlResolver.class);

    private static final Pattern GREENHOUSE_HOSTED_URL =
            Pattern.compile("^https?://(?:boards|job-boards)\\.greenhouse\\.io/([^/]+)/jobs/\\d+");

    /** A hosted Greenhouse posting URL with both the slug and the job id captured. */
    private static final Pattern GREENHOUSE_HOSTED_JOB =
            Pattern.compile("^https?://(?:boards|job-boards)\\.greenhouse\\.io/([^/]+)/jobs/(\\d+)");

    private static final Pattern LEVER_URL =
            Pattern.compile("^https?://jobs\\.lever\\.co/[^/?#]+/[0-9a-f-]{36}");

    private static final Pattern WORKDAY_HOST = Pattern.compile("^https?://[^/]*myworkday(?:jobs|site)\\.com/");

    private final AtsCompanyRepository atsCompanyRepository;
    private final EmbeddedFormDetector embeddedFormDetector;

    @Autowired
    public ApplyUrlResolver(AtsCompanyRepository atsCompanyRepository, EmbeddedFormDetector embeddedFormDetector) {
        this.atsCompanyRepository = atsCompanyRepository;
        this.embeddedFormDetector = embeddedFormDetector;
    }

    /** Test seam: no network - a company-site apply link never gets its page fetched. */
    ApplyUrlResolver(AtsCompanyRepository atsCompanyRepository) {
        this(atsCompanyRepository, EmbeddedFormDetector.offline());
    }

    /**
     * A direct, top-level application-form URL for the given job, if one can be resolved for its
     * ATS. Never throws - any unexpected failure resolving the catalog is logged at debug and
     * treated as "no direct URL", so a resolver problem never blocks the apply prompt from being
     * built.
     */
    public Optional<String> directFormUrl(JobListing job) {
        try {
            String ats = job.source();
            if (ats == null) {
                return Optional.empty();
            }
            return switch (ats) {
                case "greenhouse" -> greenhouseDirectUrl(job);
                case "lever" -> leverDirectUrl(job);
                case "linkedin", PastedUrlJobs.SOURCE -> applyLinkDirectUrl(job);
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.debug("failed to resolve direct apply form url for job {}: {}", job.jobId(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> greenhouseDirectUrl(JobListing job) {
        String slug = greenhouseSlugFromUrl(job.jobUrl())
                .or(() -> atsCompanyRepository.findByAtsAndCompany("greenhouse", job.company())
                        .map(AtsCompany::slug))
                .orElse(null);
        if (slug == null || job.sourceJobId() == null || job.sourceJobId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(greenhouseEmbedUrl(slug, job.sourceJobId()));
    }

    /**
     * The standalone, iframe-free Greenhouse application-form URL for a given board slug and ATS
     * job id (Greenhouse's {@code token} query parameter). Package-visible and reused for a
     * LinkedIn row whose resolved apply link is a hosted Greenhouse posting URL.
     */
    static String greenhouseEmbedUrl(String slug, String atsJobId) {
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return "https://job-boards.greenhouse.io/embed/job_app?for=" + encodedSlug + "&token=" + atsJobId;
    }

    private static Optional<String> greenhouseSlugFromUrl(String jobUrl) {
        if (jobUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = GREENHOUSE_HOSTED_URL.matcher(jobUrl);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> leverDirectUrl(JobListing job) {
        String jobUrl = job.jobUrl();
        if (jobUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = LEVER_URL.matcher(jobUrl);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(leverApplyUrl(matcher.group()));
    }

    /**
     * Lever's application-form URL for a given hosted posting URL: the same page with
     * {@code /apply} appended. Package-visible for the same reason as
     * {@link #greenhouseEmbedUrl(String, String)}.
     */
    static String leverApplyUrl(String hostedUrl) {
        return hostedUrl + "/apply";
    }

    /**
     * The direct apply form behind an {@code apply_url} that points at someone else's site: a
     * LinkedIn row's, read out of a signed-in Chrome before that step was removed (HANDOFF.md §13),
     * or a {@link PastedUrlJobs pasted} row's, which is the URL itself. That destination
     * is whatever LinkedIn pointed at - for Greenhouse usually the hosted posting page
     * ({@code boards.greenhouse.io/<slug>/jobs/<id>}), which some companies (Stripe) redirect to
     * an iframe page the browser tools cannot see into (HANDOFF.md §12) - so a recognised hosted
     * Greenhouse URL is rewritten to the standalone embed form, a Lever posting URL gets
     * {@code /apply}, and a URL already in that form is kept. Workday yields empty. Anything else
     * (a company's own site, a tracking redirector, a {@code grnh.se} short link) is fetched once
     * by {@link EmbeddedFormDetector}: if that page embeds a Greenhouse or Lever form, its
     * standalone URL is the direct form (this is how Stripe's {@code stripe.com/careers/listing}
     * page becomes {@code job-boards.greenhouse.io/embed/job_app?for=stripe&token=...}); otherwise
     * empty, and Claude opens {@code apply_url} as the JOB URL and finds the form itself, without
     * the "DIRECT APPLY FORM URL, no iframe, no sign-in" framing meant only for a genuinely direct URL.
     */
    private Optional<String> applyLinkDirectUrl(JobListing job) {
        String url = job.applyUrl();
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        if ("greenhouse".equals(job.applyDomain())) {
            if (url.contains("/embed/job_app?")) {
                return Optional.of(url);
            }
            Matcher m = GREENHOUSE_HOSTED_JOB.matcher(url);
            return m.find() ? Optional.of(greenhouseEmbedUrl(m.group(1), m.group(2))) : Optional.empty();
        }
        if ("lever".equals(job.applyDomain())) {
            Matcher m = LEVER_URL.matcher(url);
            if (!m.find()) {
                return Optional.empty();
            }
            return Optional.of(url.startsWith(m.group() + "/apply") ? m.group() + "/apply" : leverApplyUrl(m.group()));
        }
        if ("workday".equals(job.applyDomain()) || WORKDAY_HOST.matcher(url).find()) {
            return Optional.empty();
        }
        return embeddedFormDetector.detect(url);
    }
}
