package com.ubaid.jobdash.source;

/**
 * One ATS job-board provider (Greenhouse, Lever, Workday). Implementations must NEVER throw:
 * every failure — a dead slug, a rate-limit refusal, a transport error — is a
 * {@link SourceFetchResult} variant, never an exception. This is the same discipline as
 * {@code salary.SalarySource}, and HANDOFF.md's rule that an enrichment failure must never abort
 * a run applies here too: a broken company board must not abort the whole fetch pass.
 */
public interface JobSource {

    /** Stable provenance name: {@code "greenhouse"}, {@code "lever"} or {@code "workday"}. */
    String name();

    /** Fetches one company's board per {@code q}. Never throws — see class javadoc. */
    SourceFetchResult fetch(SourceQuery q);
}
