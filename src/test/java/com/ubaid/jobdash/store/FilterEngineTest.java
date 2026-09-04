package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.filter.FilterRuleSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lives in {@code com.ubaid.jobdash.store} (rather than {@code com.ubaid.jobdash.filter}) so it
 * can extend the package-private {@link AbstractStoreTest} and reuse its real-Flyway-migration
 * SQLite fixture, wiring a {@link FilterEngine} directly on top of the same repositories.
 */
class FilterEngineTest extends AbstractStoreTest {

    private FilterEngine filterEngine;

    private FilterEngine engine() {
        if (filterEngine == null) {
            filterEngine = new FilterEngine(excludeWordRepository, companyBlocklistRepository,
                    filterStateRepository, jobListingRepository);
        }
        return filterEngine;
    }

    private static JobCardInsert card(long jobId, String title, String company) {
        return new JobCardInsert("linkedin", String.valueOf(jobId), title, company, "Remote",
                Instant.parse("2026-08-27T00:00:00Z"),
                "https://linkedin.com/jobs/view/" + jobId, "https://linkedin.com/company/x", null);
    }

    /** Inserts a job and returns its actual (autoincrement surrogate) job_id. */
    private long insertJob(long sourceJobId, String title, String company) {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        jobListingRepository.upsertAll(List.of(card(sourceJobId, title, company)), runId, Instant.now());
        return client.sql("select job_id from job_listing where source_job_id = :id")
                .param("id", String.valueOf(sourceJobId))
                .query(Long.class)
                .single();
    }

    private JobListing find(long jobId) {
        return client.sql("select * from job_listing where job_id = :jobId")
                .param("jobId", jobId)
                .query(JobListingRepository::mapRow)
                .single();
    }

    // ---- basic evaluate() semantics ----------------------------------------------------

    @Test
    void seniorTitleIsRejectedWithReasonNamingTheWord() {
        FilterRuleSet.Verdict verdict = engine().evaluate("Senior Software Engineer", "Acme Corp");
        assertThat(verdict.verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(verdict.reason()).contains("Senior");
    }

    @Test
    void plainTitlePasses() {
        FilterRuleSet.Verdict verdict = engine().evaluate("Software Engineer", "Acme Corp");
        assertThat(verdict.verdict()).isEqualTo(FilterVerdict.PASS);
        assertThat(verdict.reason()).isNull();
    }

    @Test
    void wholeWordMatchingDoesNotFalsePositiveOnSubstrings() {
        assertThat(engine().evaluate("Leadership Development Engineer", "Acme Corp").verdict())
                .as("'Lead' must not match inside 'Leadership'")
                .isEqualTo(FilterVerdict.PASS);
        assertThat(engine().evaluate("International Engineer", "Acme Corp").verdict())
                .as("'Intern' must not match inside 'International'")
                .isEqualTo(FilterVerdict.PASS);
    }

    @Test
    void srSpecialCaseMatchesWithAndWithoutTrailingDotButNotSri() {
        assertThat(engine().evaluate("Sr Engineer", "Acme Corp").verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(engine().evaluate("Sr. Engineer", "Acme Corp").verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(engine().evaluate("Sri Lanka Program Engineer", "Acme Corp").verdict())
                .as("'Sr' must not match inside 'Sri'")
                .isEqualTo(FilterVerdict.PASS);
    }

    @Test
    void companyExactMatchDoesNotSubstringMatch() {
        companyBlocklistRepository.add("Meta", "test", null, Instant.now());

        assertThat(engine().evaluate("Software Engineer", "Meta").verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(engine().evaluate("Software Engineer", "Metabase").verdict())
                .as("'Meta' must not substring-match 'Metabase'")
                .isEqualTo(FilterVerdict.PASS);
    }

    @Test
    void companyMatchReasonNamesTheBlockedCompany() {
        companyBlocklistRepository.add("MeeBoss", "scam reports", null, Instant.now());

        FilterRuleSet.Verdict verdict = engine().evaluate("Software Engineer", "MeeBoss");
        assertThat(verdict.verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(verdict.reason()).contains("MeeBoss");
    }

    @Test
    void regexMetacharacterWordDoesNotThrowAndMatchesLiterally() {
        excludeWordRepository.add("C++", Instant.now());

        FilterRuleSet.Verdict verdict = engine().evaluate("C++ Developer", "Acme Corp");
        assertThat(verdict.verdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(verdict.reason()).contains("C++");
    }

    // ---- versioning / re-evaluation -----------------------------------------------------

    @Test
    void reevaluateStaleAppliesCurrentRulesAndStampsVersion() {
        long jobId = insertJob(100, "Backend Developer", "Acme Corp");

        int v1 = filterStateRepository.currentVersion();
        int changed1 = engine().reevaluateStale();
        assertThat(changed1).isEqualTo(1);

        JobListing afterFirstPass = find(jobId);
        assertThat(afterFirstPass.filterVerdict()).isEqualTo(FilterVerdict.PASS);
        assertThat(afterFirstPass.filterVersion()).isEqualTo(v1);

        // Add a new exclude word that now matches this row, and bump + re-evaluate.
        excludeWordRepository.add("Backend", Instant.now());
        engine().bumpVersionAndReevaluate();

        JobListing afterSecondPass = find(jobId);
        assertThat(afterSecondPass.filterVerdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(afterSecondPass.rejectReason()).contains("Backend");
        assertThat(afterSecondPass.filterVersion()).isEqualTo(filterStateRepository.currentVersion());
        assertThat(filterStateRepository.currentVersion()).isGreaterThan(v1);
    }

    @Test
    void removingExcludeWordFlipsRejectedRowBackToPassStoredAsLowercase() {
        long jobId = insertJob(200, "Senior Backend Developer", "Acme Corp");
        engine().reevaluateStale();

        JobListing rejected = find(jobId);
        assertThat(rejected.filterVerdict()).isEqualTo(FilterVerdict.REJECT);

        excludeWordRepository.delete("Senior");
        excludeWordRepository.delete("Manager"); // no-op sanity, keeps other seeded words intact
        int changed = engine().bumpVersionAndReevaluate();
        assertThat(changed).isEqualTo(1);

        JobListing flipped = find(jobId);
        assertThat(flipped.filterVerdict()).isEqualTo(FilterVerdict.PASS);
        assertThat(flipped.rejectReason()).isNull();

        // The partial index on job_detail_queue has the literal predicate filter_verdict = 'pass'
        // with no collate nocase, so verify by re-querying with exactly that literal.
        List<Long> passingLiteral = jobListingRepositoryRawPassQuery();
        assertThat(passingLiteral).contains(jobId);
    }

    private List<Long> jobListingRepositoryRawPassQuery() {
        return client.sql("select job_id from job_listing where filter_verdict = 'pass'")
                .query(Long.class)
                .list();
    }

    @Test
    void rejectedRowsAreUpdatedNeverDeletedAcrossReevaluation() {
        insertJob(300, "Senior Backend Developer", "Acme Corp");
        insertJob(301, "Backend Developer", "Acme Corp");

        engine().reevaluateStale();
        long countBefore = countJobListingRows();
        assertThat(countBefore).isEqualTo(2);

        excludeWordRepository.add("Backend", Instant.now());
        engine().bumpVersionAndReevaluate();

        long countAfter = countJobListingRows();
        assertThat(countAfter).isEqualTo(2);
    }

    private long countJobListingRows() {
        return client.sql("select count(*) from job_listing").query(Long.class).single();
    }

    @Test
    void evaluateNewRowsStampsFreshlyInsertedRowsOnly() {
        long jobId = insertJob(400, "Senior Backend Developer", "Acme Corp");

        int evaluated = engine().evaluateNewRows();
        assertThat(evaluated).isEqualTo(1);

        JobListing job = find(jobId);
        assertThat(job.filterVerdict()).isEqualTo(FilterVerdict.REJECT);
        assertThat(job.filterVersion()).isEqualTo(filterStateRepository.currentVersion());

        // A second call has nothing left to evaluate.
        assertThat(engine().evaluateNewRows()).isZero();
    }

    @Test
    void ruleSetIsBuiltOnceAndReusableAcrossMultipleEvaluations() {
        FilterRuleSet ruleSet = engine().buildRuleSet();
        Map<Long, FilterVerdict> results = Map.of(
                1L, ruleSet.evaluate("Senior Engineer", "Acme").verdict(),
                2L, ruleSet.evaluate("Engineer", "Acme").verdict());

        assertThat(results.get(1L)).isEqualTo(FilterVerdict.REJECT);
        assertThat(results.get(2L)).isEqualTo(FilterVerdict.PASS);
    }
}
