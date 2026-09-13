package com.ubaid.jobdash.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ClaudeCliClient} against stub shell scripts standing in for the real
 * {@code claude} binary — no test here may invoke the real CLI or touch the network. Per
 * HANDOFF.md §1 these assert the positive outcome (verdicts actually present, correlated by ref,
 * with the right values) rather than merely "it didn't throw."
 */
class ClaudeCliClientTest {

    @TempDir
    Path tempDir;

    private AiProperties props(String cliPath, Duration timeout) {
        return new AiProperties(true, cliPath, "sonnet", 9, 3, timeout, 6000, false, 200);
    }

    private Path stub(String name, String scriptBody) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + scriptBody);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    @Test
    void validResponseYieldsOkWithCorrelatedVerdicts() throws IOException {
        Path script = stub("valid.sh", """
                cat > /dev/null
                cat <<'EOF'
                {"is_error": false, "total_cost_usd": 0.0123, "duration_ms": 3900,
                 "structured_output": {"results": [
                    {"ref": "job-1", "recommended": true, "reason": "Matches required Kubernetes experience"},
                    {"ref": "job-2", "recommended": false, "reason": "Requires 10 years, resume shows 2"}
                 ]}}
                EOF
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        ClaudeCliResult result = client.run("dummy prompt");

        assertThat(result).isInstanceOf(ClaudeCliResult.Ok.class);
        ClaudeCliResult.Ok ok = (ClaudeCliResult.Ok) result;
        assertThat(ok.verdicts()).hasSize(2);
        assertThat(ok.verdicts()).extracting(MatchVerdict::ref).containsExactly("job-1", "job-2");
        assertThat(ok.verdicts().get(0).recommended()).isTrue();
        assertThat(ok.verdicts().get(0).reason()).contains("Kubernetes");
        assertThat(ok.verdicts().get(1).recommended()).isFalse();
        assertThat(ok.costUsd()).isEqualTo(0.0123);
        assertThat(ok.durationMs()).isEqualTo(3900);
    }

    @Test
    void isErrorTrueYieldsFailedWithCliMessage() throws IOException {
        Path script = stub("error.sh", """
                cat > /dev/null
                cat <<'EOF'
                {"is_error": true, "result": "prompt rejected by safety filter"}
                EOF
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        ClaudeCliResult result = client.run("dummy prompt");

        assertThat(result).isInstanceOf(ClaudeCliResult.Failed.class);
        assertThat(((ClaudeCliResult.Failed) result).message()).contains("safety filter");
    }

    @Test
    void malformedStdoutYieldsFailedNotAnException() throws IOException {
        Path script = stub("malformed.sh", """
                cat > /dev/null
                echo 'this is not json at all {{{'
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        ClaudeCliResult result = client.run("dummy prompt");

        assertThat(result).isInstanceOf(ClaudeCliResult.Failed.class);
    }

    @Test
    void processExceedingTimeoutIsKilledAndReturnsTimeout() throws IOException {
        Path script = stub("slow.sh", """
                cat > /dev/null
                sleep 5
                echo '{"is_error": false, "structured_output": {"results": []}}'
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofMillis(300)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        long start = System.currentTimeMillis();
        ClaudeCliResult result = client.run("dummy prompt");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isInstanceOf(ClaudeCliResult.Timeout.class);
        assertThat(((ClaudeCliResult.Timeout) result).afterMs()).isEqualTo(300);
        // The process must actually have been killed near the timeout, not left to run 5s.
        assertThat(elapsed).isLessThan(4000);
    }

    @Test
    void nonexistentCliPathYieldsCliNotFound() {
        ClaudeCliClient client = new ClaudeCliClient(
                props(tempDir.resolve("does-not-exist-binary").toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        ClaudeCliResult result = client.run("dummy prompt");

        assertThat(result).isInstanceOf(ClaudeCliResult.CliNotFound.class);
    }

    @Test
    void largeStderrVolumeDoesNotDeadlockAndStillReturnsOk() throws IOException {
        // Regression guard: a naive implementation that reads stdout to completion before
        // touching stderr will deadlock here once the OS pipe buffer for stderr fills up.
        Path script = stub("noisy-stderr.sh", """
                cat > /dev/null
                i=0
                while [ "$i" -lt 20000 ]; do
                  echo "noisy diagnostic line $i some padding to fill the pipe buffer faster" >&2
                  i=$((i+1))
                done
                echo '{"is_error": false, "total_cost_usd": 0.01, "duration_ms": 100, "structured_output": {"results": [{"ref": "job-1", "recommended": true, "reason": "fine"}]}}'
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(20)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        ClaudeCliResult result = client.run("dummy prompt");

        assertThat(result).isInstanceOf(ClaudeCliResult.Ok.class);
        assertThat(((ClaudeCliResult.Ok) result).verdicts()).hasSize(1);
    }

    @Test
    void cliOptionsArgsReachTheScriptVerbatim() throws IOException {
        Path capturedArgs = tempDir.resolve("captured-args.txt");
        Path script = stub("echo-args.sh", """
                cat > /dev/null
                echo "$@" > "%s"
                echo '{"is_error": false, "structured_output": {"ok": true}}'
                """.formatted(capturedArgs));
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        List<String> args = List.of("-p", "--chrome", "--model", "sonnet", "--output-format", "json",
                "--json-schema", "{}", "--max-turns", "5");
        ClaudeCliClient.CliOptions options = new ClaudeCliClient.CliOptions(args, Duration.ofSeconds(10), () -> false);

        CliJsonResult result = client.runStructured("dummy prompt", "{}", options);

        assertThat(result).isInstanceOf(CliJsonResult.Ok.class);
        String captured = Files.readString(capturedArgs).strip();
        assertThat(captured).isEqualTo(String.join(" ", args));
    }

    @Test
    void cancelledSupplierKillsASleepingScriptAndReturnsFailedCancelled() throws IOException {
        Path script = stub("sleepy.sh", """
                cat > /dev/null
                sleep 30
                echo '{"is_error": false, "structured_output": {"ok": true}}'
                """);
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(30)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        canceller.start();

        ClaudeCliClient.CliOptions options = new ClaudeCliClient.CliOptions(
                List.of("-p"), Duration.ofSeconds(30), cancelled::get);

        long start = System.currentTimeMillis();
        CliJsonResult result = client.runStructured("dummy prompt", "{}", options);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isInstanceOf(CliJsonResult.Failed.class);
        assertThat(((CliJsonResult.Failed) result).message()).isEqualTo("cancelled");
        // The process must actually have been killed promptly, not left to run the full 30s sleep.
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    void twoArgOverloadStillPassesSafeModeAndRestricted() throws IOException {
        Path capturedArgs = tempDir.resolve("captured-default-args.txt");
        Path script = stub("echo-default-args.sh", """
                cat > /dev/null
                echo "$@" > "%s"
                echo '{"is_error": false, "structured_output": {"results": []}}'
                """.formatted(capturedArgs));
        ClaudeCliClient client = new ClaudeCliClient(props(script.toString(), Duration.ofSeconds(10)),
                tools.jackson.databind.json.JsonMapper.builder().build());

        CliJsonResult result = client.runStructured("dummy prompt", "{}");

        assertThat(result).isInstanceOf(CliJsonResult.Ok.class);
        String captured = Files.readString(capturedArgs).strip();
        assertThat(captured).contains("--safe-mode").contains("--restricted");
    }
}
