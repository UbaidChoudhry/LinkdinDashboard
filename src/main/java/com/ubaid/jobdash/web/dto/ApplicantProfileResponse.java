package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.ApplicantProfile;

import java.time.Instant;

/** The saved applicant profile, as returned by {@code GET /api/profile} and {@code PUT /api/profile}. */
public record ApplicantProfileResponse(
        String fullName,
        String email,
        String phone,
        String location,
        String linkedinUrl,
        String portfolioUrl,
        String workAuthorization,
        boolean requiresSponsorship,
        String salaryExpectation,
        String extraAnswers,
        Instant updatedAt
) {
    public static ApplicantProfileResponse of(ApplicantProfile p) {
        return new ApplicantProfileResponse(
                p.fullName(), p.email(), p.phone(), p.location(), p.linkedinUrl(), p.portfolioUrl(),
                p.workAuthorization(), p.requiresSponsorship(), p.salaryExpectation(), p.extraAnswers(),
                p.updatedAt());
    }
}
