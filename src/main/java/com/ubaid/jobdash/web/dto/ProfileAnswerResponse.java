package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.ProfileAnswer;

import java.time.Instant;

/**
 * A {@code profile_answer} row as returned by the {@code /api/profile/answers} endpoints. Field
 * names match the frontend contract exactly: {@code id, question, answer, status, askedCount,
 * lastJobId, lastCompany, createdAt, updatedAt}.
 */
public record ProfileAnswerResponse(
        long id,
        String question,
        String answer,
        String status,
        int askedCount,
        Long lastJobId,
        String lastCompany,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProfileAnswerResponse of(ProfileAnswer a) {
        return new ProfileAnswerResponse(a.id(), a.question(), a.answer(), a.status(), a.askedCount(),
                a.lastJobId(), a.lastCompany(), a.createdAt(), a.updatedAt());
    }
}
