package com.ubaid.jobdash.ai;

/**
 * One job as presented to the resume-matching prompt. {@code ref} is an opaque id the caller
 * assigns (not necessarily the job listing's database id) so a returned {@link MatchVerdict} can
 * be correlated back to it.
 */
public record JobForMatching(String ref, String title, String company, String location, String description) {
}
