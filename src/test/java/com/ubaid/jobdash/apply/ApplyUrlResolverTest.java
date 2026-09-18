package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ApplyUrlResolver} against a stub {@link AtsCompanyRepository} - no real
 * database, since the resolver's URL/regex logic is what's under test here (the repository query
 * itself is covered by {@code AtsCompanyRepositoryTest}).
 */
class ApplyUrlResolverTest {

    /** A stub that records whether the catalog was consulted, and returns a canned answer. */
    private static final class StubAtsCompanyRepository extends AtsCompanyRepository {
        private final Optional<AtsCompany> answer;
        private boolean queried = false;

        StubAtsCompanyRepository(Optional<AtsCompany> answer) {
            super(null);
            this.answer = answer;
        }

        @Override
        public Optional<AtsCompany> findByAtsAndCompany(String ats, String company) {
            queried = true;
            return answer;
        }
    }

    private static AtsCompany atsCompany(String slug, String company) {
        return new AtsCompany(1L, "greenhouse", slug, company, null, null, true, "active",
                0, null, null, null, Instant.now());
    }

    private static JobListing job(String source, String sourceJobId, String company, String jobUrl) {
        return job(source, sourceJobId, company, jobUrl, null, null);
    }

    private static JobListing job(String source, String sourceJobId, String company, String jobUrl,
                                   String applyDomain, String applyUrl) {
        return new JobListing(
                1L, sourceJobId, source, "Backend Engineer", company, "Remote",
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"), 1L, jobUrl, "https://company.com",
                FilterVerdict.PASS, 1, null, (UserStatus) null, null, null, null, applyUrl, applyDomain,
                "desc", "hash", null, null, null, null, false, null, null, null, null);
    }

    @Test
    void embeddedStripeStyleUrlWithCatalogHitResolvesToEmbedUrl() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.of(atsCompany("stripe", "Stripe")));
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("greenhouse", "7988264", "Stripe", "https://stripe.com/jobs/search?gh_jid=7988264");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=7988264");
        assertThat(repo.queried).isTrue();
    }

    @Test
    void hostedGreenhouseUrlParsesSlugWithoutTouchingTheRepository() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("greenhouse", "12345", "Airbnb",
                "https://job-boards.greenhouse.io/airbnb/jobs/12345");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).contains("https://job-boards.greenhouse.io/embed/job_app?for=airbnb&token=12345");
        assertThat(repo.queried).isFalse();
    }

    @Test
    void legacyBoardsHostedGreenhouseUrlAlsoParsesSlug() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("greenhouse", "99", "Coinbase",
                "https://boards.greenhouse.io/coinbase/jobs/99");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).contains("https://job-boards.greenhouse.io/embed/job_app?for=coinbase&token=99");
        assertThat(repo.queried).isFalse();
    }

    @Test
    void greenhouseWithNoCatalogRowAndNoSlugInUrlResolvesToEmpty() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("greenhouse", "7988264", "Unknown Co",
                "https://unknownco.com/jobs/search?gh_jid=7988264");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).isEmpty();
        assertThat(repo.queried).isTrue();
    }

    @Test
    void leverUrlResolvesToApplySuffix() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("lever", "abc", "Palantir",
                "https://jobs.lever.co/palantir/1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).contains("https://jobs.lever.co/palantir/1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f/apply");
    }

    @Test
    void leverUrlWithQueryStringStillYieldsCleanApplySuffix() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("lever", "abc", "Palantir",
                "https://jobs.lever.co/palantir/1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f?lever-source=LinkedIn");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).contains("https://jobs.lever.co/palantir/1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f/apply");
    }

    @Test
    void workdayResolvesToEmptyWithoutTouchingTheRepository() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.of(atsCompany("nvidia", "NVIDIA")));
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("workday", "1", "NVIDIA", "https://nvidia.wd5.myworkdayjobs.com/job/1");

        Optional<String> result = resolver.directFormUrl(job);

        assertThat(result).isEmpty();
        assertThat(repo.queried).isFalse();
    }

    @Test
    void linkedinWithNoApplyDomainResolvesToEmpty() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("linkedin", "1", "Acme", "https://www.linkedin.com/jobs/view/1");

        assertThat(resolver.directFormUrl(job)).isEmpty();
    }

    @Test
    void linkedinLinkToAHostedGreenhousePostingBecomesTheEmbedForm() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("linkedin", "1", "Stripe", "https://www.linkedin.com/jobs/view/1",
                "greenhouse", "https://boards.greenhouse.io/stripe/jobs/6042172?gh_src=linkedin");

        assertThat(resolver.directFormUrl(job))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=6042172");
        assertThat(repo.queried).as("a LinkedIn row with a stored apply_url never re-queries the catalog").isFalse();
    }

    @Test
    void linkedinLinkAlreadyAnEmbedFormOrAShortLinkIsKeptOrLeftAlone() {
        ApplyUrlResolver resolver = new ApplyUrlResolver(new StubAtsCompanyRepository(Optional.empty()));

        assertThat(resolver.directFormUrl(job("linkedin", "1", "Stripe", "https://www.linkedin.com/jobs/view/1",
                "greenhouse", "https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=42")))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=42");
        assertThat(resolver.directFormUrl(job("linkedin", "1", "CLEAR", "https://www.linkedin.com/jobs/view/1",
                "grnh.se", "https://grnh.se/70ehaj5i1us"))).isEmpty();
    }

    @Test
    void linkedinLinkToALeverPostingGetsApplyAppendedOnce() {
        ApplyUrlResolver resolver = new ApplyUrlResolver(new StubAtsCompanyRepository(Optional.empty()));
        String posting = "https://jobs.lever.co/wealthfront/f5a0963a-ca1a-4140-b9e6-dbf6072093fe";

        assertThat(resolver.directFormUrl(job("linkedin", "1", "Wealthfront", "https://www.linkedin.com/jobs/view/1",
                "lever", posting + "?lever-source=LinkedIn"))).contains(posting + "/apply");
        assertThat(resolver.directFormUrl(job("linkedin", "1", "Wealthfront", "https://www.linkedin.com/jobs/view/1",
                "lever", posting + "/apply"))).contains(posting + "/apply");
    }

    @Test
    void linkedinLinkToACompanyCareersPageIsResolvedThroughTheEmbeddedFormDetector() {
        List<String> fetched = new ArrayList<>();
        EmbeddedFormDetector detector = new EmbeddedFormDetector(url -> {
            fetched.add(url);
            return Optional.of("<noscript><iframe src=\"https://job-boards.greenhouse.io/embed/job_app?for=stripe"
                    + "&amp;token=8062305\"></iframe></noscript>");
        });
        ApplyUrlResolver resolver = new ApplyUrlResolver(new StubAtsCompanyRepository(Optional.empty()), detector);
        String stripe = "https://stripe.com/careers/listing/full-stack-engineer-link/8062305?gh_src=73vnei";

        assertThat(resolver.directFormUrl(job("linkedin", "1", "Stripe", "https://www.linkedin.com/jobs/view/1",
                "stripe.com", stripe)))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=8062305");
        assertThat(fetched).containsExactly(stripe);
    }

    @Test
    void companyCareersPageWithoutAnEmbeddedFormResolvesToEmpty() {
        ApplyUrlResolver resolver = new ApplyUrlResolver(new StubAtsCompanyRepository(Optional.empty()),
                new EmbeddedFormDetector(url -> Optional.of("<html>no form here</html>")));

        assertThat(resolver.directFormUrl(job("linkedin", "1", "Acme", "https://www.linkedin.com/jobs/view/1",
                "acme.com", "https://acme.com/careers/1"))).isEmpty();
    }

    @Test
    void linkedinMatchedToWorkdayResolvesToEmpty() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        // A detector that would "find" a form on any page - Workday must never be asked.
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo, new EmbeddedFormDetector(url -> {
            throw new AssertionError("Workday apply links must not be fetched: " + url);
        }));

        JobListing job = job("linkedin", "1", "NVIDIA", "https://www.linkedin.com/jobs/view/1",
                "workday", "https://nvidia.wd5.myworkdayjobs.com/job/1");

        assertThat(resolver.directFormUrl(job)).isEmpty();
    }
}
