package com.ubaid.jobdash.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the local {@code claude} CLI, in restricted/safe mode with no session persistence, to
 * score a batch of jobs against a resume. The prompt (which contains the user's resume) is piped
 * on stdin rather than passed as an argv argument — passed as an argument the CLI stalls ~3s
 * waiting for stdin it never receives, and argv has a ~1MB ceiling a batched prompt can breach.
 * <p>
 * stdout and stderr are drained on separate threads concurrently with the process running: a
 * process whose stderr pipe fills while only stdout is read will deadlock, so both must be read
 * as the process produces output, not after it exits.
 * <p>
 * Never throws — every failure path (missing binary, timeout, non-zero exit, unparseable output,
 * interruption) is represented as a {@link ClaudeCliResult} variant. The prompt body is never
 * logged; it contains the user's resume.
 */
@Component
public class ClaudeCliClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public ClaudeCliClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Scores a batch of jobs against a resume. A thin, job-match-shaped view over
     * {@link #runStructured}: it supplies the match schema and maps the returned JSON into
     * {@link MatchVerdict}s. Kept as its own method so existing callers and test doubles that
     * override it are unaffected by the generic path underneath.
     */
    public ClaudeCliResult run(String prompt) {
        CliJsonResult json = runStructured(prompt, MatchPromptBuilder.RESULT_JSON_SCHEMA);
        return switch (json) {
            case CliJsonResult.Ok ok -> new ClaudeCliResult.Ok(
                    toVerdicts(ok.structuredOutput()), ok.costUsd(), ok.durationMs());
            case CliJsonResult.CliNotFound notFound -> new ClaudeCliResult.CliNotFound(notFound.message());
            case CliJsonResult.Timeout timeout -> new ClaudeCliResult.Timeout(timeout.afterMs());
            case CliJsonResult.Failed failed -> new ClaudeCliResult.Failed(failed.message(), failed.exitCode());
        };
    }

    private static List<MatchVerdict> toVerdicts(JsonNode structuredOutput) {
        List<MatchVerdict> verdicts = new ArrayList<>();
        JsonNode results = structuredOutput.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                verdicts.add(new MatchVerdict(
                        r.path("ref").asString(""),
                        r.path("recommended").asBoolean(false),
                        r.path("reason").asString(""),
                        optionalInt(r, "salaryMin"),
                        optionalInt(r, "salaryMax")));
            }
        }
        return verdicts;
    }

    /** Reads an optional integer field, distinguishing "absent/null" (returns null) from present. */
    private static Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    /**
     * Runs one CLI invocation constrained to {@code jsonSchema} and hands back the resulting
     * {@code structured_output} object untouched, so any caller can define its own response shape.
     *
     * <p>Never throws: every failure — a missing binary, a timeout, a non-zero exit, unparseable
     * output, an interrupt — comes back as a {@link CliJsonResult} variant.
     */
    public CliJsonResult runStructured(String prompt, String jsonSchema) {
        List<String> command = List.of(
                properties.cliPath(),
                "-p",
                "--model", properties.model(),
                "--output-format", "json",
                "--json-schema", jsonSchema,
                "--no-session-persistence",
                "--safe-mode",
                "--restricted");

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("No such file") || msg.contains("error=2")) {
                return new CliJsonResult.CliNotFound(
                        "Claude CLI not found at '" + properties.cliPath()
                                + "'. Install the Claude CLI, or set CLAUDE_CLI_PATH / ai.cli-path.");
            }
            log.debug("failed to start claude CLI: {}", msg);
            return new CliJsonResult.Failed("failed to start claude CLI: " + msg, -1);
        }

        // Drain stdout and stderr concurrently with the process running, on separate threads,
        // so a full stderr pipe can never block us while we wait on stdout (or vice versa).
        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream());
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutDrain, "claude-cli-stdout");
        Thread stderrThread = new Thread(stderrDrain, "claude-cli-stderr");
        stdoutThread.start();
        stderrThread.start();

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The process may still produce a usable result (or a meaningful error) even if
            // writing stdin failed partway through; let waitFor()/parsing below decide.
            log.debug("failed writing prompt to claude CLI stdin: {}", e.toString());
        }

        boolean finished;
        try {
            finished = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CliJsonResult.Failed("interrupted while waiting for claude CLI", -1);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Timeout(properties.timeout().toMillis());
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
        int exitCode = process.exitValue();
        String stdout = stdoutDrain.text();
        String stderr = stderrDrain.text();
        log.debug("claude CLI exited {} ({} bytes stdout, {} bytes stderr)",
                exitCode, stdout.length(), stderr.length());

        return parse(stdout, stderr, exitCode);
    }

    private CliJsonResult parse(String stdout, String stderr, int exitCode) {
        try {
            JsonNode root = objectMapper.readTree(stdout);
            boolean isError = root.path("is_error").asBoolean(false);
            JsonNode structured = root.get("structured_output");
            if (isError || structured == null || structured.isNull()) {
                String fallback = stderr.isBlank() ? "claude CLI reported an error" : stderr;
                String message = root.path("result").asString(fallback);
                return new CliJsonResult.Failed(message, exitCode);
            }
            double costUsd = root.path("total_cost_usd").asDouble(0.0);
            long durationMs = root.path("duration_ms").asLong(0L);
            return new CliJsonResult.Ok(structured, costUsd, durationMs);
        } catch (RuntimeException e) {
            log.debug("failed to parse claude CLI output: {}", e.toString());
            return new CliJsonResult.Failed("could not parse claude CLI output: " + e.getMessage(), exitCode);
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads an entire stream to completion on its own thread, so it never blocks its sibling. */
    private static final class StreamDrain implements Runnable {
        private final InputStream in;
        private volatile String text = "";

        StreamDrain(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (InputStream stream = in) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                stream.transferTo(buffer);
                text = buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("error draining claude CLI stream: {}", e.toString());
            }
        }

        String text() {
            return text;
        }
    }
}
