package com.ubaid.jobdash.web.dto;

/** Uniform error body for every non-2xx response the API returns. Never carries a stack trace. */
public record ErrorResponse(String message) {
}
