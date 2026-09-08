package com.ubaid.jobdash.source.location;

import java.time.Instant;

/**
 * One cached decision about whether a location string refers to the United States, as made by
 * Claude. Mirrors a {@code location_verdict} row 1:1.
 *
 * @param locationKey    the normalised key; see {@link LocationKey}.
 * @param locationSample one raw string that produced this key, kept for debugging only.
 * @param inUs           Claude's call.
 * @param confident      Claude's own confidence. False means the string genuinely does not say
 *                        where the job is (Workday's {@code "11 Locations"} placeholder, a bare
 *                        {@code "San Jose"}); such rows are kept and flagged, never dropped.
 */
public record LocationVerdict(
        String locationKey,
        String locationSample,
        boolean inUs,
        boolean confident,
        String model,
        Instant decidedAt
) {
}
