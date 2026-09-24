package com.ubaid.jobdash.source.search;

/**
 * What the LinkedIn guest job-detail fragment ({@code /jobs-guest/jobs/api/jobPosting/{id}})
 * yields once parsed. Only {@code description} and {@code descriptionHash} are persisted today;
 * the criteria fields are parsed because they are free and cheap to keep, but nothing stores
 * them yet.
 *
 * @param title           the posting title from the top card; may be null.
 * @param company         the company name from the top card; may be null.
 * @param description     the job description as plain text with paragraph breaks preserved.
 *                         Never null or blank - a fragment with no description does not parse.
 * @param descriptionHash {@code md5(lower(whitespace-collapsed description))}, the relay-detection
 *                         key HANDOFF.md §5 specifies.
 * @param seniorityLevel  e.g. {@code "Mid-Senior level"}; may be null.
 * @param employmentType  e.g. {@code "Full-time"}; may be null.
 * @param jobFunction     e.g. {@code "Engineering and Information Technology"}; may be null.
 * @param industries      e.g. {@code "Design Services"}; may be null.
 * @param applyKind       {@code "onsite"} (Easy Apply), {@code "offsite"}, or null when neither
 *                        marker is present. Informational only - see {@code HANDOFF.md} §13.
 */
public record JobDetail(
        String title,
        String company,
        String description,
        String descriptionHash,
        String seniorityLevel,
        String employmentType,
        String jobFunction,
        String industries,
        String applyKind
) {
}
