package com.ubaid.jobdash.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    /**
     * Per-call overrides for {@link #runStructured(String, String, CliOptions)}: {@code args} is
     * the FULL flag list after the binary path (callers own their own flag set — the default
     * scan flags live in the two-arg overload, {@code apply.ApplyOrchestrator} builds its own
     * Chrome-driving set), {@code timeout} replaces {@code ai.timeout} for this call, and
     * {@code cancelled} is polled between 1-second wait slices so a batch's cancel button can
     * kill an in-flight process promptly instead of only at the timeout.
     */
    public record CliOptions(List<String> args, Duration timeout, BooleanSupplier cancelled) {
    }

    /**
     * One event surfaced while a {@link #runStreaming} call is in flight. {@code kind} is one of
     * {@code text} (an assistant text chunk), {@code tool} (a tool_use call), {@code tool_result} /
     * {@code tool_error} (a tool's result, split by {@code is_error}), or {@code done} (the final
     * {@code result} line, carrying its {@code subtype}). {@code text} is always truncated to the
     * first 300 characters of whatever the underlying stream line carried.
     */
    public record CliEvent(String kind, String text) {
    }

    /**
     * Per-call overrides for {@link #runStreaming}, the stream-json analogue of {@link CliOptions}:
     * {@code idleTimeout} additionally kills the process (independent of {@code timeout}) when no
     * stdout line has arrived for that long - guards against a frozen page (e.g. a native OS file
     * dialog) that leaves the CLI itself alive but silent.
     */
    public record StreamOptions(List<String> args, Duration timeout, Duration idleTimeout, BooleanSupplier cancelled) {
    }

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
        List<String> args = List.of(
                "-p",
                "--model", properties.model(),
                "--output-format", "json",
                "--json-schema", jsonSchema,
                "--no-session-persistence",
                "--safe-mode",
                "--restricted");
        return runStructured(prompt, jsonSchema, new CliOptions(args, properties.timeout(), () -> false));
    }

    /**
     * As {@link #runStructured(String, String)}, but the caller supplies the full flag list
     * (everything after the binary path — so callers own their own flag set, e.g. an
     * apply/ApplyOrchestrator invocation needs {@code --chrome} instead of {@code --safe-mode}),
     * the per-call timeout, and a cancellation flag polled between 1-second wait slices. On
     * cancellation the process is killed with {@link Process#destroyForcibly()} and
     * {@code Failed("cancelled", -1)} is returned.
     */
    public CliJsonResult runStructured(String prompt, String jsonSchema, CliOptions options) {
        List<String> command = new ArrayList<>();
        command.add(properties.cliPath());
        command.addAll(options.args());

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

        // Waited for in 1-second slices (rather than one blocking waitFor(timeout)) so a
        // cancellation flag flipped mid-run is noticed promptly instead of only at the deadline.
        long deadlineNanos = System.nanoTime() + options.timeout().toNanos();
        boolean finished = false;
        boolean cancelled = false;
        try {
            while (!finished && System.nanoTime() < deadlineNanos) {
                if (options.cancelled().getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                long sliceMs = Math.min(TimeUnit.SECONDS.toMillis(1), TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                finished = process.waitFor(Math.max(0, sliceMs), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CliJsonResult.Failed("interrupted while waiting for claude CLI", -1);
        }

        if (cancelled) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Failed("cancelled", -1);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Timeout(options.timeout().toMillis());
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
            return buildResult(root, stderr, exitCode);
        } catch (RuntimeException e) {
            log.debug("failed to parse claude CLI output: {}", e.toString());
            return new CliJsonResult.Failed("could not parse claude CLI output: " + e.getMessage(), exitCode);
        }
    }

    /**
     * Runs one {@code --output-format stream-json} CLI invocation, delivering a {@link CliEvent}
     * to {@code listener} for every assistant text chunk, tool call, and tool result as they
     * arrive on stdout, rather than only at exit — so an in-flight (or cancelled/killed) call
     * leaves a trail of what Claude was doing. The final return value is built from the stream's
     * last {@code result} line exactly like {@link #runStructured(String, String, CliOptions)}
     * builds it from the whole-blob {@code json} format.
     *
     * <p>Same process/stdin/stderr-drain discipline as {@link #runStructured(String, String, CliOptions)}:
     * never throws, stderr is drained concurrently with stdout so a full pipe can't deadlock the
     * call, and cancellation kills the process and returns {@code Failed("cancelled", -1)}. In
     * addition, the process is killed and {@code Failed("no activity from claude for <n>s - killed", -1)}
     * is returned if no stdout line arrives for {@code options.idleTimeout()}.
     */
    public CliJsonResult runStreaming(String prompt, String jsonSchema, StreamOptions options,
                                       Consumer<CliEvent> listener) {
        List<String> command = new ArrayList<>();
        command.add(properties.cliPath());
        command.addAll(options.args());

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
        LineDrain stdoutDrain = new LineDrain(process.getInputStream(), objectMapper, listener);
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutDrain, "claude-cli-stdout-stream");
        Thread stderrThread = new Thread(stderrDrain, "claude-cli-stderr");
        stdoutThread.start();
        stderrThread.start();

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("failed writing prompt to claude CLI stdin: {}", e.toString());
        }

        // Waited for in 1-second slices, as in runStructured, so cancellation and idle-timeout
        // are both noticed promptly rather than only at the overall deadline.
        long deadlineNanos = System.nanoTime() + options.timeout().toNanos();
        long idleNanos = options.idleTimeout().toNanos();
        boolean finished = false;
        boolean cancelled = false;
        boolean idleKilled = false;
        try {
            while (!finished && System.nanoTime() < deadlineNanos) {
                if (options.cancelled().getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() - stdoutDrain.lastEventNanos() > idleNanos) {
                    idleKilled = true;
                    break;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                long sliceMs = Math.min(TimeUnit.SECONDS.toMillis(1), TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                finished = process.waitFor(Math.max(0, sliceMs), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CliJsonResult.Failed("interrupted while waiting for claude CLI", -1);
        }

        if (cancelled) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Failed("cancelled", -1);
        }

        if (idleKilled) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Failed(
                    "no activity from claude for " + options.idleTimeout().toSeconds() + "s - killed", -1);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new CliJsonResult.Timeout(options.timeout().toMillis());
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
        int exitCode = process.exitValue();
        String stderr = stderrDrain.text();

        JsonNode resultLine = stdoutDrain.lastResult();
        if (resultLine == null) {
            String message = stderr.isBlank() ? "claude CLI produced no result" : stderr;
            return new CliJsonResult.Failed(message, exitCode);
        }
        return buildResult(resultLine, stderr, exitCode);
    }

    /** Shared by {@link #parse} and {@link #runStreaming}: the {@code result}-line-shaped payload. */
    private static CliJsonResult buildResult(JsonNode root, String stderr, int exitCode) {
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
    }

    /**
     * Delivers one event to the listener, never letting a listener's own bug break the CLI run
     * it's merely observing.
     */
    private static void publishQuietly(Consumer<CliEvent> listener, CliEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.debug("claude CLI event listener threw: {}", e.toString());
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

    /**
     * Reads stdout LINE BY LINE (rather than to completion, like {@link StreamDrain}) on its own
     * thread, parsing each line as one {@code stream-json} event, delivering 0..n {@link CliEvent}s
     * to the listener as they arrive, and retaining the final {@code result} line for the caller
     * to build its return value from. A line that isn't valid JSON is ignored (logged at debug) -
     * it never breaks the run.
     */
    private static final class LineDrain implements Runnable {
        private final InputStream in;
        private final ObjectMapper objectMapper;
        private final Consumer<CliEvent> listener;
        private volatile JsonNode lastResult;
        private volatile long lastEventNanos = System.nanoTime();

        LineDrain(InputStream in, ObjectMapper objectMapper, Consumer<CliEvent> listener) {
            this.in = in;
            this.objectMapper = objectMapper;
            this.listener = listener;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastEventNanos = System.nanoTime();
                    processLine(line);
                }
            } catch (IOException e) {
                log.debug("error draining claude CLI stdout stream: {}", e.toString());
            }
        }

        private void processLine(String line) {
            if (line.isBlank()) {
                return;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (RuntimeException e) {
                log.debug("ignoring non-JSON line from claude CLI stdout: {}", e.toString());
                return;
            }
            switch (node.path("type").asString("")) {
                case "assistant" -> emitAssistantEvents(node);
                case "user" -> emitUserEvents(node);
                case "result" -> {
                    lastResult = node;
                    publishQuietly(listener, new CliEvent("done", node.path("subtype").asString("")));
                }
                default -> {
                    // system/init, rate_limit_event, and anything else we don't yet know about.
                }
            }
        }

        private void emitAssistantEvents(JsonNode node) {
            JsonNode content = node.path("message").path("content");
            if (!content.isArray()) {
                return;
            }
            for (JsonNode item : content) {
                switch (item.path("type").asString("")) {
                    case "text" -> publishQuietly(listener, new CliEvent("text", truncate(item.path("text").asString(""))));
                    case "tool_use" -> {
                        String name = item.path("name").asString("");
                        JsonNode input = item.get("input");
                        String rendered = name + " " + (input == null ? "{}" : input.toString());
                        publishQuietly(listener, new CliEvent("tool", truncate(rendered)));
                    }
                    default -> {
                        // ignore other content block types
                    }
                }
            }
        }

        private void emitUserEvents(JsonNode node) {
            JsonNode content = node.path("message").path("content");
            if (!content.isArray()) {
                return;
            }
            for (JsonNode item : content) {
                if (!"tool_result".equals(item.path("type").asString(""))) {
                    continue;
                }
                boolean isError = item.path("is_error").asBoolean(false);
                String text = extractToolResultText(item.get("content"));
                publishQuietly(listener, new CliEvent(isError ? "tool_error" : "tool_result", truncate(text)));
            }
        }

        /** {@code tool_result}'s own content is either a plain string or {@code [{"type":"text","text":...}]}. */
        private static String extractToolResultText(JsonNode content) {
            if (content == null || content.isNull()) {
                return "";
            }
            if (content.isString()) {
                return content.asString("");
            }
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asString(""))) {
                        text.append(item.path("text").asString(""));
                    }
                }
                return text.toString();
            }
            return content.toString();
        }

        private static String truncate(String s) {
            return s.length() <= 300 ? s : s.substring(0, 300);
        }

        JsonNode lastResult() {
            return lastResult;
        }

        long lastEventNanos() {
            return lastEventNanos;
        }
    }
}
