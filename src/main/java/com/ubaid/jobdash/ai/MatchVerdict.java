package com.ubaid.jobdash.ai;

/**
 * The Claude CLI's judgment on a single job, correlated back to the caller's job via {@code ref}
 * (the opaque id echoed in {@link JobForMatching#ref()}).
 * <p>
 * {@code salaryMin}/{@code salaryMax} are the annualized-USD salary the model found explicitly
 * stated in the job description, or {@code null} when the description doesn't state one. A
 * posting-stated figure is ground truth and takes precedence over an estimated one - see
 * {@code ResumeMatchService#applyPostingSalaryIfValid}.
 */
public record MatchVerdict(String ref, boolean recommended, String reason, Integer salaryMin, Integer salaryMax) {

    /** Convenience constructor for callers with no salary to report - existing behavior, unchanged. */
    public MatchVerdict(String ref, boolean recommended, String reason) {
        this(ref, recommended, reason, null, null);
    }
}
