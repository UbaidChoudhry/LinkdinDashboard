package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Lever serves one at {@code <jobUrl>/apply}. Every other ATS (Workday, LinkedIn, unknown) has no
 * known equivalent, so this resolves to empty for them.
 */
@Component
public class ApplyUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(ApplyUrlResolver.class);

    private static final Pattern GREENHOUSE_HOSTED_URL =
            Pattern.compile("^https?://(?:boards|job-boards)\\.greenhouse\\.io/([^/]+)/jobs/\\d+");

    private static final Pattern LEVER_URL =
            Pattern.compile("^https?://jobs\\.lever\\.co/[^/?#]+/[0-9a-f-]{36}");

    private final AtsCompanyRepository atsCompanyRepository;

    public ApplyUrlResolver(AtsCompanyRepository atsCompanyRepository) {
        this.atsCompanyRepository = atsCompanyRepository;
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
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return Optional.of("https://job-boards.greenhouse.io/embed/job_app?for=" + encodedSlug
                + "&token=" + job.sourceJobId());
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
        return Optional.of(matcher.group() + "/apply");
    }
}
