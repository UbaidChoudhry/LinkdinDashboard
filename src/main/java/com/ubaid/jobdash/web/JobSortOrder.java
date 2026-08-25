package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.JobListing;

import java.time.Instant;
import java.util.Comparator;

/**
 * The job-list sort order used by {@code GET /api/jobs}, deliberately a two-bucket placeholder:
 * rows with a non-null {@code salary_max} first, sorted descending; then rows with no salary,
 * sorted by {@code posted_at} descending (newest first). Nothing populates salary yet (a later
 * part fills it from other sources), so every row currently lands in the second bucket — that's
 * expected, not a bug, and this comparator is already correct for when it changes.
 */
final class JobSortOrder {

    static final Comparator<JobListing> DEFAULT = JobSortOrder::compare;

    private JobSortOrder() {
    }

    private static int compare(JobListing a, JobListing b) {
        boolean aHasSalary = a.salaryMax() != null;
        boolean bHasSalary = b.salaryMax() != null;
        if (aHasSalary != bHasSalary) {
            return aHasSalary ? -1 : 1;
        }
        if (aHasSalary) {
            return Double.compare(b.salaryMax(), a.salaryMax());
        }
        return compareInstantsDesc(a.postedAt(), b.postedAt());
    }

    private static int compareInstantsDesc(Instant a, Instant b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }
}
