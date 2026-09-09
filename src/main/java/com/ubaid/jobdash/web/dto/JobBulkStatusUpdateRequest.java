package com.ubaid.jobdash.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/jobs/bulk-status}. Same {@code status} contract as
 * {@link JobStatusUpdateRequest} - {@code "applied"}, {@code "not_interested"}, or
 * {@code null}/absent to clear back to untriaged - applied to every id in {@code jobIds}.
 */
public record JobBulkStatusUpdateRequest(List<Long> jobIds, String status) {
}
