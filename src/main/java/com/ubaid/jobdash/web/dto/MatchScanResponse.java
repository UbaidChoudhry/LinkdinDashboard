package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.ai.ResumeMatchService.ScanResult;
import com.ubaid.jobdash.source.location.RemoteClassifier.ClassifyResult;

/**
 * Response of {@code POST /api/matches/scan}: {@link ScanResult} 1:1, plus how many rows the
 * remote pass that runs first decided ({@code remoteDecided}, {@code remoteFound} of them remote).
 */
public record MatchScanResponse(
        int scanned,
        int recommended,
        int notRecommended,
        int skipped,
        int failedBatches,
        double totalCostUsd,
        String errorMessage,
        int remoteDecided,
        int remoteFound
) {
    public static MatchScanResponse of(ScanResult r, ClassifyResult remote) {
        return new MatchScanResponse(r.scanned(), r.recommended(), r.notRecommended(), r.skipped(),
                r.failedBatches(), r.totalCostUsd() + remote.costUsd(), r.errorMessage(),
                remote.decided(), remote.remote());
    }
}
