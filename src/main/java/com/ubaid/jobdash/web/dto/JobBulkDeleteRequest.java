package com.ubaid.jobdash.web.dto;

import java.util.List;

/** Body of {@code POST /api/jobs/bulk-delete}: the ids to permanently remove. */
public record JobBulkDeleteRequest(List<Long> jobIds) {
}
