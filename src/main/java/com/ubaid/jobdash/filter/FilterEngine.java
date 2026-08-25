package com.ubaid.jobdash.filter;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.CompanyBlocklistRepository;
import com.ubaid.jobdash.store.ExcludeWordRepository;
import com.ubaid.jobdash.store.FilterStateRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link FilterRuleSet}s from the current exclude-word / company-blocklist repository
 * state and applies them to {@code job_listing} rows.
 *
 * <p>Filtering happens entirely locally: LinkedIn's guest search endpoint silently ignores
 * facet parameters, and boolean NOT clauses in the {@code keywords} query param match the job
 * *description*, not the title — both filter the wrong field. So every exclusion is evaluated
 * here against the stored {@code title}/{@code company} instead.
 */
@Service
public class FilterEngine {

    private final ExcludeWordRepository excludeWordRepository;
    private final CompanyBlocklistRepository companyBlocklistRepository;
    private final FilterStateRepository filterStateRepository;
    private final JobListingRepository jobListingRepository;

    public FilterEngine(ExcludeWordRepository excludeWordRepository,
                         CompanyBlocklistRepository companyBlocklistRepository,
                         FilterStateRepository filterStateRepository,
                         JobListingRepository jobListingRepository) {
        this.excludeWordRepository = excludeWordRepository;
        this.companyBlocklistRepository = companyBlocklistRepository;
        this.filterStateRepository = filterStateRepository;
        this.jobListingRepository = jobListingRepository;
    }

    /** Builds a fresh rule set from the exclude-word list and company blocklist right now. */
    public FilterRuleSet buildRuleSet() {
        List<String> words = excludeWordRepository.list();
        List<String> companies = companyBlocklistRepository.list().stream()
                .map(CompanyBlocklistRepository.BlockedCompany::company)
                .toList();
        return FilterRuleSet.build(words, companies);
    }

    /** Evaluates a single (title, company) pair against a freshly built rule set. */
    public FilterRuleSet.Verdict evaluate(String title, String company) {
        return buildRuleSet().evaluate(title, company);
    }

    /**
     * Re-evaluates every row whose {@code filter_version} is behind the current version (or
     * unset) against a freshly built rule set, and writes back the verdict/reason/version for
     * all of them. Rows that flip {@code reject -> pass} automatically re-enter the detail
     * queue, since the partial index predicate {@code filter_verdict = 'pass'} now matches
     * them. Nothing is ever deleted.
     *
     * @return the number of rows whose verdict actually changed
     */
    public int reevaluateStale() {
        int currentVersion = filterStateRepository.currentVersion();
        List<JobListing> stale = jobListingRepository.findWithFilterVersionLessThan(currentVersion);
        if (stale.isEmpty()) {
            return 0;
        }

        FilterRuleSet ruleSet = buildRuleSet();
        List<JobListingRepository.VerdictUpdate> updates = new ArrayList<>(stale.size());
        int changed = 0;
        for (JobListing job : stale) {
            FilterRuleSet.Verdict verdict = ruleSet.evaluate(job.title(), job.company());
            if (job.filterVerdict() != verdict.verdict()) {
                changed++;
            }
            updates.add(new JobListingRepository.VerdictUpdate(job.jobId(), verdict.verdict(), verdict.reason()));
        }
        jobListingRepository.applyVerdicts(updates, currentVersion);
        return changed;
    }

    /**
     * Bumps the filter version, then re-evaluates every stale row against it. Any mutation of
     * the exclude-word list or the company blocklist must call this so the change takes effect
     * across every already-stored row, not just future ones.
     *
     * @return the number of rows whose verdict changed
     */
    public int bumpVersionAndReevaluate() {
        filterStateRepository.bumpVersion();
        return reevaluateStale();
    }

    /**
     * Applies the current rule set to rows that have never been evaluated (freshly inserted by
     * a sweep, {@code filter_verdict is null}), stamping them with the current filter version.
     *
     * @return the number of rows evaluated
     */
    public int evaluateNewRows() {
        List<JobListing> unfiltered = jobListingRepository.findWithoutVerdict();
        if (unfiltered.isEmpty()) {
            return 0;
        }

        int currentVersion = filterStateRepository.currentVersion();
        FilterRuleSet ruleSet = buildRuleSet();
        List<JobListingRepository.VerdictUpdate> updates = new ArrayList<>(unfiltered.size());
        for (JobListing job : unfiltered) {
            FilterRuleSet.Verdict verdict = ruleSet.evaluate(job.title(), job.company());
            updates.add(new JobListingRepository.VerdictUpdate(job.jobId(), verdict.verdict(), verdict.reason()));
        }
        jobListingRepository.applyVerdicts(updates, currentVersion);
        return updates.size();
    }
}
