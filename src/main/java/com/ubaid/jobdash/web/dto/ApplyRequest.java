package com.ubaid.jobdash.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/applications}.
 *
 * @param jobIds   jobs to apply to, in order; must be non-empty and every id must exist.
 * @param resumeId resume to apply with; null means the current default resume.
 * @param submit   boxed {@link Boolean} - null means {@code false} (fill and stop before Submit).
 */
public record ApplyRequest(List<Long> jobIds, Long resumeId, Boolean submit) {
}
