package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code resume} table. Mirrors the schema in
 * {@code src/main/resources/db/migration/V7__resume_and_ai_match.sql} 1:1; {@code uploadedAt}
 * is represented as an {@link Instant} and converted to/from ISO-8601 text via
 * {@link Timestamps}.
 */
public record Resume(
        long id,
        String name,
        String originalFilename,
        String contentType,
        String storedPath,
        String contentText,
        int charCount,
        boolean isDefault,
        Instant uploadedAt
) {
}
