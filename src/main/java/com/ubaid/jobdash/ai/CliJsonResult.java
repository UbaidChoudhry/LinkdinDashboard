package com.ubaid.jobdash.ai;

import tools.jackson.databind.JsonNode;

/**
 * The outcome of one schema-constrained {@code claude} CLI invocation, carrying whatever
 * {@code structured_output} object the caller's own JSON schema produced.
 *
 * <p>This is the payload-agnostic primitive: {@link ClaudeCliResult} is the job-match-shaped view
 * built on top of it. Sealed, in the style of {@code http/FetchResult}, so callers must switch
 * exhaustively.
 */
public sealed interface CliJsonResult {

    /** The CLI answered and produced a {@code structured_output} object matching the schema. */
    record Ok(JsonNode structuredOutput, double costUsd, long durationMs) implements CliJsonResult {
    }

    /** The binary could not be found. Retrying other batches against it is pointless. */
    record CliNotFound(String message) implements CliJsonResult {
    }

    /** The process exceeded {@code ai.timeout} and was killed. */
    record Timeout(long afterMs) implements CliJsonResult {
    }

    /**
     * The CLI ran but reported an error, or its output could not be parsed. {@code costUsd} is
     * whatever {@code total_cost_usd} the CLI reported before stopping early (0.0 when the CLI
     * never got that far, e.g. a missing binary or malformed output) - a stopped-early run still
     * burned real spend that the caller must not silently record as $0.
     */
    record Failed(String message, int exitCode, double costUsd) implements CliJsonResult {
        public Failed(String message, int exitCode) {
            this(message, exitCode, 0.0);
        }
    }
}
