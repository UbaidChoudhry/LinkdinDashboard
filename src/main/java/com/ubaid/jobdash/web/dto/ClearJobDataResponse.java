package com.ubaid.jobdash.web.dto;

/** Result of POST /api/data/clear. {@code databaseSizeBytesAfter} confirms VACUUM actually shrank the file. */
public record ClearJobDataResponse(int jobsCleared, int runsCleared, long databaseSizeBytesAfter) {
}
