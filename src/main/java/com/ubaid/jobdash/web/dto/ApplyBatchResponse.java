package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.ApplyBatch;

import java.time.Instant;
import java.util.List;

/**
 * One {@code apply_batch} row plus its per-job rows, as returned by the {@code /api/applications}
 * endpoints. {@code newQuestions} is how many new {@code profile_answer} rows this batch's run
 * created; {@code hasReport} tells the UI whether {@code GET /api/applications/{id}/report} will
 * return anything.
 */
public record ApplyBatchResponse(
        long id,
        long resumeId,
        boolean submit,
        String status,
        int total,
        int done,
        int submitted,
        int needsReview,
        int failed,
        int skipped,
        double costUsd,
        Instant startedAt,
        Instant finishedAt,
        List<JobApplicationResponse> jobs,
        int newQuestions,
        boolean hasReport
) {
    public static ApplyBatchResponse of(ApplyBatch b, List<JobApplicationResponse> jobs) {
        return new ApplyBatchResponse(
                b.id(), b.resumeId(), b.submit(), b.status(), b.total(), b.done(), b.submitted(),
                b.needsReview(), b.failed(), b.skipped(), b.costUsd(), b.startedAt(), b.finishedAt(), jobs,
                b.newQuestions(), b.reportPath() != null && !b.reportPath().isBlank());
    }
}
