package com.ubaid.jobdash.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/matches/scan}.
 *
 * @param runId    run to scan; null means the most recent run. Ignored when {@code jobIds} is given.
 * @param resumeId resume to score against; null means the current default resume.
 * @param jobIds   when non-null/non-empty, re-scans exactly these jobs instead of a run's set.
 */
public record MatchScanRequest(Long runId, Long resumeId, List<Long> jobIds) {
}
