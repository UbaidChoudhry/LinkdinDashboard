package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.JobListing;

import java.time.Instant;

/**
 * A job listing as exposed to the UI. Deliberately narrower than {@link JobListing}: leaves out
 * {@code description}, {@code descriptionHash}, {@code suppressed} and {@code detailFetchedAt},
 * which are internal to the detail-fetch pipeline and not meant for display.
 */
public record JobResponse(
        long jobId,
        String title,
        String company,
        String location,
        Instant postedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long lastSeenRunId,
        String jobUrl,
        String companyUrl,
        String filterVerdict,
        String rejectReason,
        String userStatus,
        Instant userStatusAt,
        String detailStatus,
        String applyUrl,
        String applyDomain,
        Double salaryMin,
        Double salaryMax,
        String salarySource
) {

    public static JobResponse from(JobListing job) {
        return new JobResponse(
                job.jobId(),
                job.title(),
                job.company(),
                job.location(),
                job.postedAt(),
                job.firstSeenAt(),
                job.lastSeenAt(),
                job.lastSeenRunId(),
                job.jobUrl(),
                job.companyUrl(),
                job.filterVerdict() == null ? null : job.filterVerdict().toDb(),
                job.rejectReason(),
                job.userStatus() == null ? null : job.userStatus().toDb(),
                job.userStatusAt(),
                job.detailStatus(),
                job.applyUrl(),
                job.applyDomain(),
                job.salaryMin(),
                job.salaryMax(),
                job.salarySource()
        );
    }
}
