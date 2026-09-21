package com.ubaid.jobdash.apply;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link PastedUrlJobs} against a real temp-file database; no network. */
class PastedUrlJobsTest extends AbstractStoreTest {

    @Test
    void parseTrimsSkipsBlankLinesAndDropsRepeatsInFirstSeenOrder() {
        List<String> urls = PastedUrlJobs.parse(Arrays.asList(
                "  https://jobs.lever.co/acme/1  ", "", null, "https://boards.greenhouse.io/acme/jobs/2",
                "https://jobs.lever.co/acme/1"));

        assertThat(urls).containsExactly("https://jobs.lever.co/acme/1", "https://boards.greenhouse.io/acme/jobs/2");
    }

    @Test
    void parseRejectsAnythingButAnAbsoluteHttpUrlAndNamesTheLine() {
        assertThatThrownBy(() -> PastedUrlJobs.parse(List.of("https://ok.example/jobs/1", "Software Engineer at Acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"Software Engineer at Acme\"");
        assertThatThrownBy(() -> PastedUrlJobs.parse(List.of("ftp://acme.com/jobs/1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PastedUrlJobs.parse(List.of("acme.com/jobs/1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void companyGuessReadsTheBoardSlugOrTenantOtherwiseTheHost() {
        assertThat(PastedUrlJobs.companyGuess("https://job-boards.greenhouse.io/stripe/jobs/8062305")).isEqualTo("stripe");
        assertThat(PastedUrlJobs.companyGuess("https://job-boards.greenhouse.io/embed/job_app?for=zoominfo&token=1"))
                .isEqualTo("zoominfo");
        assertThat(PastedUrlJobs.companyGuess("https://jobs.lever.co/shieldai/0f8ae1f2")).isEqualTo("shieldai");
        assertThat(PastedUrlJobs.companyGuess("https://jobs.ashbyhq.com/ramp/123")).isEqualTo("ramp");
        assertThat(PastedUrlJobs.companyGuess(
                "https://dowjones.wd1.myworkdayjobs.com/en-US/Dow_Jones_Career/job/NYC/SE-II_55287")).isEqualTo("dowjones");
        assertThat(PastedUrlJobs.companyGuess("https://wd5.myworkdaysite.com/recruiting/nasdaq/Global/job/1"))
                .isEqualTo("nasdaq");
        assertThat(PastedUrlJobs.companyGuess("https://www.stripe.com/careers/listing/x/8062305")).isEqualTo("stripe.com");
    }

    @Test
    void importWritesAPastedRowWithTheUrlAsItsApplyTargetAndReusesItWhenPastedAgain() {
        PastedUrlJobs pastedUrlJobs = new PastedUrlJobs(jobListingRepository);
        String greenhouse = "https://job-boards.greenhouse.io/stripe/jobs/8062305";
        String workday = "https://dowjones.wd1.myworkdayjobs.com/en-US/Dow_Jones_Career/job/NYC/SE-II_55287";

        List<Long> first = pastedUrlJobs.importUrls(List.of(greenhouse, workday), Instant.now());
        List<Long> again = pastedUrlJobs.importUrls(List.of(workday), Instant.now());

        assertThat(first).hasSize(2).doesNotHaveDuplicates();
        assertThat(again).containsExactly(first.get(1));

        JobListing row = jobListingRepository.findById(first.get(0)).orElseThrow();
        assertThat(row.source()).isEqualTo(PastedUrlJobs.SOURCE);
        assertThat(row.jobUrl()).isEqualTo(greenhouse);
        assertThat(row.applyUrl()).isEqualTo(greenhouse);
        assertThat(row.applyDomain()).isEqualTo("greenhouse");
        assertThat(row.title()).isEqualTo(PastedUrlJobs.TITLE_PLACEHOLDER);
        assertThat(row.company()).isEqualTo("stripe");
        assertThat(jobListingRepository.findById(first.get(1)).orElseThrow().applyDomain()).isEqualTo("workday");
    }

    @Test
    void theFilterEngineNeverPicksUpAPastedRowSoItCannotReachTheResultsTab() {
        long pasted = new PastedUrlJobs(jobListingRepository)
                .importUrls(List.of("https://jobs.lever.co/acme/1"), Instant.now()).get(0);

        assertThat(jobListingRepository.findWithoutVerdict()).extracting(JobListing::jobId).doesNotContain(pasted);
        assertThat(jobListingRepository.findWithFilterVersionLessThan(Integer.MAX_VALUE))
                .extracting(JobListing::jobId).doesNotContain(pasted);
    }

    @Test
    void onlyAPastedRowCanBeRenamed() {
        long pasted = new PastedUrlJobs(jobListingRepository)
                .importUrls(List.of("https://jobs.lever.co/acme/1"), Instant.now()).get(0);

        assertThat(jobListingRepository.setPastedTitleAndCompany(pasted, "Platform Engineer", "Acme")).isEqualTo(1);
        assertThat(jobListingRepository.findById(pasted).orElseThrow().title()).isEqualTo("Platform Engineer");

        jobListingRepository.upsertAll(List.of(new com.ubaid.jobdash.store.JobCardInsert("greenhouse", "7",
                "Backend Engineer", "Acme", null, null, "https://job-boards.greenhouse.io/acme/jobs/7", null, null)),
                1, Instant.now());
        long greenhouse = jobListingRepository.findIdBySourceKey("greenhouse", "7").orElseThrow();
        assertThat(jobListingRepository.setPastedTitleAndCompany(greenhouse, "x", "y")).isZero();
        assertThat(jobListingRepository.findById(greenhouse).orElseThrow().title()).isEqualTo("Backend Engineer");
    }
}
