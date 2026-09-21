package com.ubaid.jobdash.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/applications/urls}: apply to job postings by URL, from the Apply tab.
 *
 * @param urls        one posting URL per entry, in order; blank entries and repeats are ignored.
 * @param resumeId    resume to apply with; null means the current default resume.
 * @param submit      boxed {@link Boolean} - null means {@code false} (fill and stop before Submit).
 * @param concurrency how many applications to fill at once, 1 to 5; null means {@code apply.concurrency}.
 */
public record ApplyUrlsRequest(List<String> urls, Long resumeId, Boolean submit, Integer concurrency) {
}
