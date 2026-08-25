package com.ubaid.jobdash.web.dto;

/**
 * Body of {@code POST /api/filters/companies}. {@code reason} and {@code evidence} are optional;
 * {@code company_blocklist.reason} is {@code not null} in the schema, so a blank reason is
 * defaulted to {@code "manually blocked"} rather than rejected.
 */
public record CompanyBlocklistRequest(String company, String reason, String evidence) {
}
