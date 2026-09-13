package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.domain.JobListing;

import java.time.Instant;

/**
 * One job's outcome within an apply batch, as returned inside {@link ApplyBatchResponse}.
 * {@code title}/{@code company} are looked up from {@code job_listing} in one batched query per
 * batch (never per row) and are {@code ""} if that job row is gone.
 */
public record JobApplicationResponse(
        long id,
        long jobId,
        String title,
        String company,
        String status,
        String notes,
        double costUsd,
        Instant startedAt,
        Instant finishedAt
) {
    public static JobApplicationResponse of(JobApplication a, JobListing job) {
        return new JobApplicationResponse(
                a.id(), a.jobId(),
                job == null ? "" : job.title(),
                job == null ? "" : job.company(),
                a.status(), a.notes(), a.costUsd(), a.startedAt(), a.finishedAt());
    }
}
