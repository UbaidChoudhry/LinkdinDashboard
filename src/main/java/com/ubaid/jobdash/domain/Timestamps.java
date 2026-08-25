package com.ubaid.jobdash.domain;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Centralizes the {@link Instant} &lt;-&gt; ISO-8601 text conversion used for every
 * timestamp column in the SQLite schema. SQLite has no native datetime type, so all
 * timestamps are persisted as {@code text} using {@link DateTimeFormatter#ISO_INSTANT}
 * (e.g. {@code 2026-08-28T20:51:00Z}), and read back into {@link Instant}.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /** Converts an {@link Instant} to the ISO-8601 text form stored in SQLite. Null-safe. */
    public static String toText(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /** Parses the ISO-8601 text form stored in SQLite back into an {@link Instant}. Null-safe. */
    public static Instant parse(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
