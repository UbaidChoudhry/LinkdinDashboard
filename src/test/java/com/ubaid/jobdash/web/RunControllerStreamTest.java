package com.ubaid.jobdash.web;

import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.sweep.RunProgressRegistry;
import com.ubaid.jobdash.sweep.SweepProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@code RunController.pushProgress}'s emitter-completion decision against a real
 * streaming request, same real-temp-SQLite MockMvc spirit as {@link DataControllerTest} (built
 * manually per HANDOFF §4 - {@code spring-boot-starter-webmvc-test} ships no
 * {@code @AutoConfigureMockMvc} in this Boot version).
 *
 * <p>Reproduces the phase-boundary race documented on {@code RunController.pushProgress} and the
 * frontend's {@code isRunFinished()}: {@code SweepService.collect()} publishes the LinkedIn
 * phase's own outcome (e.g. {@code "ok"}) into the shared {@link RunProgressRegistry} the instant
 * collection ends, before the next phase publishes its own in-flight status a moment later. The
 * 750ms SSE poll can land inside that gap, where the registry says a terminal-looking status but
 * {@code sweep_run.finished_at} is still null because {@code RunOrchestrator.finishRun()} hasn't
 * run yet. The stream must stay open through that; it must complete once {@code finished_at} is
 * actually set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RunControllerStreamTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("jobdash-stream-test.db"));
    }

    @Autowired
    private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private SweepRunRepository sweepRunRepository;
    @Autowired
    private RunProgressRegistry runProgressRegistry;

    @BeforeEach
    void resetData() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        client.sql("delete from sweep_run").update();
    }

    private long createRun() {
        return sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
    }

    private static SweepProgress progressWithStatus(long runId, String status) {
        return new SweepProgress(runId, status, null, 0, 0, 0, 0, false, 0, 0, 0, 0, "linkedin", null);
    }

    @Test
    void streamStaysOpenWhenStatusLooksTerminalButFinishedAtIsStillNull() throws Exception {
        long runId = createRun();
        // Mirrors SweepService.collect() the instant it returns "ok": the registry already
        // carries a terminal-looking status, but finishRun() (which persists finished_at) has
        // not run - the run row from createRun() above still has finished_at = null.
        runProgressRegistry.start(runId, progressWithStatus(runId, "ok"));

        MvcResult result = mockMvc.perform(get("/api/runs/{id}/stream", runId))
                .andExpect(request().asyncStarted())
                .andReturn();

        // The poller's first tick fires immediately (0 initial delay). Give it time to run
        // without waiting long enough to reach a second tick (poll interval is 750ms).
        Thread.sleep(200);
        assertThat(result.getRequest().isAsyncStarted())
                .as("stream must stay open while finished_at is null, even though status alone looks terminal")
                .isTrue();

        // Let the stream actually finish so no scheduled poll task lingers into another test.
        sweepRunRepository.finish(runId, Instant.now(), "ok");
        result.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test
    void streamCompletesOnceStatusIsTerminalAndFinishedAtIsSet() throws Exception {
        long runId = createRun();
        runProgressRegistry.start(runId, progressWithStatus(runId, "ok"));
        // The pairing RunOrchestrator.finishRun() actually produces: finished_at persisted before
        // (here, simply already present when) the registry's terminal status is read.
        sweepRunRepository.finish(runId, Instant.now(), "ok");

        MvcResult result = mockMvc.perform(get("/api/runs/{id}/stream", runId))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
