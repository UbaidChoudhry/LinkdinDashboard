package com.ubaid.jobdash.web.dto;

/**
 * Body of {@code POST /api/sources} — a manual catalog addition. {@code ats} is one of
 * {@code greenhouse}/{@code lever}/{@code workday}. For Greenhouse/Lever, {@code slug} is
 * required. For Workday, either supply {@code host} (+ optional {@code site}) directly, or
 * {@code careersUrl} — a full careers URL such as
 * {@code https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/...} — which the
 * server parses into host and site; this is the override for tenants whose site id can't be
 * auto-discovered from robots.txt.
 */
public record CreateSourceRequest(String ats, String slug, String company, String host, String site,
                                   String careersUrl) {
}
