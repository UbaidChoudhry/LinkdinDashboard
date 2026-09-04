package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.ai.ResumeMatchService.ScanResult;

/** Response of {@code POST /api/matches/scan}, mirroring {@link ScanResult} 1:1. */
public record MatchScanResponse(
        int scanned,
        int recommended,
        int notRecommended,
        int skipped,
        int failedBatches,
        double totalCostUsd,
        String errorMessage
) {
    public static MatchScanResponse of(ScanResult r) {
        return new MatchScanResponse(r.scanned(), r.recommended(), r.notRecommended(), r.skipped(),
                r.failedBatches(), r.totalCostUsd(), r.errorMessage());
    }
}
