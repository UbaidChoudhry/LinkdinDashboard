package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code profile_answer} table: one question the applicant has (or hasn't yet)
 * answered, used to fill application-form fields the resume itself doesn't cover. Replaces the
 * old single free-text {@code applicant_profile.extra_answers} field with one row per distinct
 * question (see {@code src/main/resources/db/migration/V14__profile_answers.sql}).
 * <p>
 * {@code status} is {@code answered} or {@code pending}, stored lowercase (HANDOFF §3) - a
 * blank {@code answer} is always {@code pending}. {@code askedCount}/{@code lastJobId}/
 * {@code lastCompany} track how often, and where, a still-unanswered question has come up across
 * apply batches; they are never reset when the question is finally answered.
 */
public record ProfileAnswer(
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
}
