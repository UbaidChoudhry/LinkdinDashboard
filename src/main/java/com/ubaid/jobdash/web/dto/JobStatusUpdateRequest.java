package com.ubaid.jobdash.web.dto;

/**
 * Body of {@code POST /api/jobs/{id}/status}. {@code status} must be {@code "applied"},
 * {@code "not_interested"}, or {@code null}/absent to clear the status back to untriaged.
 */
public record JobStatusUpdateRequest(String status) {
}
