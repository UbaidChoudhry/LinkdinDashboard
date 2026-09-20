package com.ubaid.jobdash.apply;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-HTML tests for {@link EmbeddedFormDetector} - no network; the fetch is a canned function. */
class EmbeddedFormDetectorTest {

    /** The shape of Stripe's careers listing page (2026-09-23): the Greenhouse form iframe inside a noscript. */
    private static final String STRIPE_LISTING = """
            <html><body><div class="careers-apply-page__mask" aria-hidden="true"></div>
            <noscript><iframe class="careers-apply-page__iframe"
              src="https://job-boards.greenhouse.io/embed/job_app?for=stripe&amp;token=8062305"></iframe></noscript>
            <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"listing":{"greenhouseId":8062305}}}}</script>
            </body></html>
            """;

    private static final String STRIPE_URL = "https://stripe.com/careers/listing/full-stack-engineer-link/8062305?gh_src=73vnei";

    @Test
    void stripeStyleNoscriptIframeYieldsTheGreenhouseEmbedForm() {
        assertThat(EmbeddedFormDetector.detectInHtml(STRIPE_LISTING, STRIPE_URL))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=8062305");
    }

    @Test
    void boardLoaderPlusJobIdInPageYieldsTheEmbedForm() {
        String html = """
                <script src="https://boards.greenhouse.io/embed/job_board/js?for=acme"></script>
                <script>Grnhse.Iframe.load(4012345006);</script>
                """;
        assertThat(EmbeddedFormDetector.detectInHtml(html, "https://acme.com/careers/backend-engineer"))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=acme&token=4012345006");
    }

    @Test
    void boardLoaderPlusGhJidInTheUrlYieldsTheEmbedForm() {
        String html = "<script src=\"https://boards.greenhouse.io/embed/job_board/js?for=acme\"></script>";
        assertThat(EmbeddedFormDetector.detectInHtml(html, "https://acme.com/careers?gh_jid=4012345006"))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=acme&token=4012345006");
    }

    @Test
    void boardLoaderWithNoJobIdAnywhereYieldsEmpty() {
        String html = "<script src=\"https://boards.greenhouse.io/embed/job_board/js?for=acme\"></script>";
        assertThat(EmbeddedFormDetector.detectInHtml(html, "https://acme.com/careers")).isEmpty();
    }

    @Test
    void leverPostingReferencedByThePageGetsApplyAppended() {
        String html = "<iframe src=\"https://jobs.lever.co/wealthfront/f5a0963a-ca1a-4140-b9e6-dbf6072093fe\"></iframe>";
        assertThat(EmbeddedFormDetector.detectInHtml(html, "https://wealthfront.com/careers/1"))
                .contains("https://jobs.lever.co/wealthfront/f5a0963a-ca1a-4140-b9e6-dbf6072093fe/apply");
    }

    @Test
    void pageWithNoEmbeddedFormYieldsEmpty() {
        assertThat(EmbeddedFormDetector.detectInHtml("<html><body>Apply by email</body></html>",
                "https://acme.com/careers/1")).isEmpty();
        assertThat(EmbeddedFormDetector.detectInHtml("", "https://acme.com")).isEmpty();
        assertThat(EmbeddedFormDetector.detectInHtml(null, "https://acme.com")).isEmpty();
    }

    @Test
    void detectUsesTheFetcherAndSwallowsItsFailures() {
        EmbeddedFormDetector working = new EmbeddedFormDetector(url -> Optional.of(STRIPE_LISTING));
        assertThat(working.detect(STRIPE_URL))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=8062305");

        EmbeddedFormDetector failing = new EmbeddedFormDetector(url -> {
            throw new IllegalStateException("boom");
        });
        assertThat(failing.detect(STRIPE_URL)).isEmpty();
        assertThat(EmbeddedFormDetector.offline().detect(STRIPE_URL)).isEmpty();
        assertThat(working.detect(null)).isEmpty();
    }

    @Test
    void botWalledPageFallsBackToASlugGuessConfirmedByTheGreenhouseApi() {
        String zoominfo = "https://www.zoominfo.com/careers?gh_jid=8623285002&gh_src=d14a9e1e2";
        java.util.List<String> fetched = new java.util.ArrayList<>();
        EmbeddedFormDetector detector = new EmbeddedFormDetector(url -> {
            fetched.add(url);
            if (url.equals(zoominfo)) {
                return Optional.empty(); // the site answers 403
            }
            return url.equals(EmbeddedFormDetector.BOARDS_API + "zoominfo/jobs/8623285002")
                    ? Optional.of("{\"id\":8623285002}") : Optional.empty();
        });

        assertThat(detector.detect(zoominfo))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=zoominfo&token=8623285002");
        assertThat(fetched).containsExactly(zoominfo, EmbeddedFormDetector.BOARDS_API + "zoominfo/jobs/8623285002");
    }

    @Test
    void stripePageVariantWithoutTheIframeStillResolvesViaGreenhouseIdAndTheApi() {
        String html = "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"listing\":{\"greenhouseId\":8062305}}}}</script>";
        EmbeddedFormDetector detector = new EmbeddedFormDetector(url ->
                url.startsWith(EmbeddedFormDetector.BOARDS_API)
                        ? (url.endsWith("stripe/jobs/8062305") ? Optional.of("{}") : Optional.empty())
                        : Optional.of(html));

        assertThat(detector.detect("https://stripe.com/careers/listing/full-stack-engineer-link/8062305?gh_src=x"))
                .contains("https://job-boards.greenhouse.io/embed/job_app?for=stripe&token=8062305");
    }

    @Test
    void aGuessTheApiRejectsYieldsEmpty() {
        EmbeddedFormDetector detector = new EmbeddedFormDetector(url ->
                url.startsWith(EmbeddedFormDetector.BOARDS_API) ? Optional.empty() : Optional.of("<html>no form</html>"));

        assertThat(detector.detect("https://acme.com/careers/job/1234567")).isEmpty();
        assertThat(detector.detect("https://acme.com/careers/job/backend-engineer")).isEmpty();
    }

    @Test
    void slugFromHostTakesTheLabelBeforeTheTopLevelDomain() {
        assertThat(EmbeddedFormDetector.slugFromHost("https://www.zoominfo.com/careers?x=1")).contains("zoominfo");
        assertThat(EmbeddedFormDetector.slugFromHost("https://stripe.com/careers/1")).contains("stripe");
        assertThat(EmbeddedFormDetector.slugFromHost("https://careers.vizientinc.com/x")).contains("vizientinc");
        assertThat(EmbeddedFormDetector.slugFromHost("https://jobs.example.co.uk/x")).contains("example");
        assertThat(EmbeddedFormDetector.slugFromHost("not a url")).isEmpty();
    }
}
