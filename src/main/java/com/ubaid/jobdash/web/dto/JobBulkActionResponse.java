package com.ubaid.jobdash.web.dto;

/** Result of a bulk job action (status update or delete): how many rows were actually affected. */
public record JobBulkActionResponse(int count) {
}
