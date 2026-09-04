package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.AiMatch;

/** One cached verdict, as returned by {@code GET /api/matches}. */
public record MatchResponse(long jobId, boolean recommended, String reason, String model) {
    public static MatchResponse of(AiMatch m) {
        return new MatchResponse(m.jobId(), m.recommended(), m.reason(), m.model());
    }
}
