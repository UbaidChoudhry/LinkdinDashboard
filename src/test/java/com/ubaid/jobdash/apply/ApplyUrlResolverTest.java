package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        return new JobListing(
                1L, sourceJobId, source, "Backend Engineer", company, "Remote",
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"), 1L, jobUrl, "https://company.com",
                FilterVerdict.PASS, 1, null, (UserStatus) null, null, null, null, null, null,
                "desc", "hash", null, null, null, null, false, null, null);
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
    void linkedinResolvesToEmpty() {
        StubAtsCompanyRepository repo = new StubAtsCompanyRepository(Optional.empty());
        ApplyUrlResolver resolver = new ApplyUrlResolver(repo);

        JobListing job = job("linkedin", "1", "Acme", "https://www.linkedin.com/jobs/view/1");

        assertThat(resolver.directFormUrl(job)).isEmpty();
    }
}
