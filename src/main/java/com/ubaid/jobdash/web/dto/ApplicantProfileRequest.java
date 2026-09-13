package com.ubaid.jobdash.web.dto;

/**
 * Body of {@code PUT /api/profile}: every field of {@link com.ubaid.jobdash.domain.ApplicantProfile}
 * except {@code updatedAt} (set server-side from the {@link java.time.Clock} bean).
 * {@code requiresSponsorship} is a boxed {@link Boolean} defaulting to {@code false} when absent
 * (Jackson 3 null-into-primitive trap - see HANDOFF §4). {@code fullName} and {@code email} are
 * required; a blank value for either is a 400.
 */
public record ApplicantProfileRequest(
        String fullName,
        String email,
        String phone,
        String location,
        String linkedinUrl,
        String portfolioUrl,
        String workAuthorization,
        Boolean requiresSponsorship,
        String salaryExpectation,
        String extraAnswers
) {
}
