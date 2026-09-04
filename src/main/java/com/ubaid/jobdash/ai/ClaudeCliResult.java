package com.ubaid.jobdash.ai;

import java.util.List;

/**
 * Outcome of {@link ClaudeCliClient#run}. A sealed hierarchy, in the style of
 * {@code http.FetchResult}, so callers must handle each distinct way the CLI call did or didn't
 * produce verdicts.
 */
public sealed interface ClaudeCliResult {

    /** The CLI ran, exited 0, and returned parseable structured output. */
    record Ok(List<MatchVerdict> verdicts, double costUsd, long durationMs) implements ClaudeCliResult {
    }

    /** The configured binary is not on PATH / could not be launched. */
    record CliNotFound(String message) implements ClaudeCliResult {
    }

    /** The process did not finish within {@code ai.timeout} and was forcibly killed. */
    record Timeout(long afterMs) implements ClaudeCliResult {
    }

    /** The CLI ran but reported an error, exited non-zero, or returned unparseable output. */
    record Failed(String message, int exitCode) implements ClaudeCliResult {
    }
}
