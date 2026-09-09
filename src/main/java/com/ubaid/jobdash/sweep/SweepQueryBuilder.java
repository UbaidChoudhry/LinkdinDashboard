package com.ubaid.jobdash.sweep;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds LinkedIn guest URLs: the job-search pages the sweep's pagination loop walks, and the
 * per-posting detail fragment the detail-fetch phase reads afterwards.
 * <p>
 * Deliberately omits {@code spellCorrectionEnabled} and {@code currentJobId} — both broaden or
 * leak UI state rather than narrow the query — and never encodes a boolean NOT clause into
 * {@code keywords}: LinkedIn applies keyword booleans against the job <em>description</em>, not
 * the title, so a NOT clause there filters almost the wrong field. All exclusion for this sweep
 * is applied locally, after fetch (task 6's concern, not this one's).
 */
public final class SweepQueryBuilder {

    private static final String BASE_URL =
            "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search";
    private static final String DETAIL_BASE_URL =
            "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/";

    private SweepQueryBuilder() {
    }

    /**
     * @param keywords the plain (non-boolean) search keywords.
     * @param location the search location — a metro name/shard, or the run's single location.
     * @param hours    the recency window in hours; converted to LinkedIn's {@code f_TPR=r<seconds>}.
     * @param start    the pagination offset, stepped by 10 by the caller.
     */
    public static URI buildUri(String keywords, String location, int hours, int start) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "keywords", keywords);
        appendParam(query, "location", location);
        appendParam(query, "f_TPR", "r" + (hours * 3600L));
        appendParam(query, "sortBy", "DD");
        appendParam(query, "start", String.valueOf(start));
        return URI.create(BASE_URL + "?" + query);
    }

    /**
     * The guest job-detail fragment for one posting: the only anonymous endpoint that carries the
     * description (HANDOFF.md §2). {@code jobId} is LinkedIn's numeric posting id, i.e. the row's
     * {@code source_job_id}.
     */
    public static URI buildDetailUri(String jobId) {
        return URI.create(DETAIL_BASE_URL + encode(jobId));
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(key).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
