package com.ubaid.jobdash.ai;

/**
 * The Claude CLI's judgment on a single job, correlated back to the caller's job via {@code ref}
 * (the opaque id echoed in {@link JobForMatching#ref()}).
 */
public record MatchVerdict(String ref, boolean recommended, String reason) {
}
