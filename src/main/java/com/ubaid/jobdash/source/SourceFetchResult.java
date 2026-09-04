package com.ubaid.jobdash.source;

import java.util.List;

/**
 * Outcome of {@link JobSource#fetch}. A sealed hierarchy, in the same style as
 * {@code http.FetchResult}, so callers must handle each distinct reason a fetch did or didn't
 * produce jobs rather than treating every non-exception outcome as success.
 */
public sealed interface SourceFetchResult {

    /** The board was fetched (and, for Workday, details resolved up to the run's cap). */
    record Ok(List<SourcedJob> jobs, int requestsMade) implements SourceFetchResult {
    }

    /**
     * The board itself doesn't exist: an HTTP 404 from Greenhouse/Lever, or a Workday host whose
     * site id could not be resolved. Distinct from an alive board with zero postings (Lever
     * returns HTTP 200 with {@code []} for that — never treat an empty array as a dead slug).
     */
    record DeadSlug(String message) implements SourceFetchResult {
    }

    /** Refused before any request was sent: the source's daily cap or pacing gate blocked it. */
    record RateLimited(String message) implements SourceFetchResult {
    }

    /** A request was attempted but failed at the transport level, or the response was unusable. */
    record TransportError(String message) implements SourceFetchResult {
    }
}
