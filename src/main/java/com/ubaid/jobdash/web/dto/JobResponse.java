package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.domain.JobListing;

import java.time.Instant;

/**
 * A job listing as exposed to the UI. Deliberately narrower than {@link JobListing}: leaves out
 * {@code descriptionHash}, {@code suppressed} and {@code detailFetchedAt}, which are internal to
 * the detail-fetch pipeline and not meant for display.
 *
 * <p>{@code aiRecommended}/{@code aiReason} come from the caller's cached {@link AiMatch}
 * verdict against whichever resume the request resolved (explicit or default) - null on both
 * means "not scanned" (no resume, or not yet scanned against this one), never "not recommended".
 *
 * <p>{@code locationUs}/{@code locationConfident} carry Claude's judgement of the free-form
 * location string. Both null means "not classified yet" - which is not the same as "not in the
 * US". {@code locationConfident == false} means Claude answered but the string genuinely does not
 * say where the job is; the UI flags those rather than hiding them.
 */
public record JobResponse(
        long jobId,
        String source,
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
        String salarySource,
        String salarySourceDetail,
        Boolean aiRecommended,
        String aiReason,
        Boolean locationUs,
        Boolean locationConfident,
        String applicationStatus,
        String applicationNotes,
        String applyKind,
        String applyMatchNote
) {

    /** Builds a response with no AI match context - {@code aiRecommended}/{@code aiReason} are null. */
    public static JobResponse from(JobListing job) {
        return from(job, null, null);
    }

    /** Builds a response, populating {@code aiRecommended}/{@code aiReason} from a cached verdict, if any. */
    public static JobResponse from(JobListing job, AiMatch aiMatch) {
        return from(job, aiMatch, null);
    }

    /**
     * Builds a response, additionally populating {@code applicationStatus}/{@code applicationNotes}
     * from the most recent {@link JobApplication} row for this job, if any - null on both means
     * "never attempted", not "failed".
     */
    public static JobResponse from(JobListing job, AiMatch aiMatch, JobApplication application) {
        return new JobResponse(
                job.jobId(),
                job.source(),
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
                job.salarySource(),
                job.salarySourceDetail(),
                aiMatch == null ? null : aiMatch.recommended(),
                aiMatch == null ? null : aiMatch.reason(),
                job.locationUs(),
                job.locationConfident(),
                application == null ? null : application.status(),
                application == null ? null : application.notes(),
                job.applyKind(),
                job.applyMatchNote()
        );
    }
}
