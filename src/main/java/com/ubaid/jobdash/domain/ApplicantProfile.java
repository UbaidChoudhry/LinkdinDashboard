package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * The single row of the {@code applicant_profile} table (id is always 1): the free-text answers
 * used to fill application-form fields that the resume itself doesn't answer. Mirrors the
 * schema in {@code src/main/resources/db/migration/V12__applications.sql} 1:1; {@code updatedAt}
 * is represented as an {@link Instant} and converted to/from ISO-8601 text via {@link Timestamps}.
 */
public record ApplicantProfile(
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
}
