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
}
