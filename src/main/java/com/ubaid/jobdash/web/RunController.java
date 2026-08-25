package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.http.CircuitStateStore;
import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.sweep.SweepProgress;
import com.ubaid.jobdash.sweep.SweepRunRequest;
import com.ubaid.jobdash.sweep.SweepService;
import com.ubaid.jobdash.web.dto.CreateRunRequest;
import com.ubaid.jobdash.web.dto.RunIdResponse;
import com.ubaid.jobdash.web.dto.RunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REST surface for kicking off, watching, listing and cancelling sweep runs. All the actual
 * pagination/rate-limiting/circuit-breaking work lives in {@link SweepService}; this controller
 * only translates HTTP in and out of it.
 */
@RestController
public class RunController {

    private static final int DEFAULT_HOURS = 24;
    private static final int DEFAULT_RECENT_LIMIT = 20;
    /** How often the SSE stream polls the progress registry and pushes an event. */
    private static final long SSE_POLL_MS = 750;
    /** Generous emitter timeout so a slow/long run's stream is never dropped early. */
    private static final long SSE_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final SweepService sweepService;
    private final SweepRunRepository sweepRunRepository;
    private final CircuitStateStore circuitStateStore;
    private final Clock clock;
    private final ScheduledExecutorService sseScheduler;

    public RunController(SweepService sweepService, SweepRunRepository sweepRunRepository,
                          CircuitStateStore circuitStateStore, Clock clock, ScheduledExecutorService sseScheduler) {
        this.sweepService = sweepService;
        this.sweepRunRepository = sweepRunRepository;
        this.circuitStateStore = circuitStateStore;
        this.clock = clock;
        this.sseScheduler = sseScheduler;
    }

    @PostMapping("/api/runs")
    public ResponseEntity<RunIdResponse> createRun(@RequestBody(required = false) CreateRunRequest body) {
        CreateRunRequest request = body == null ? new CreateRunRequest(null, null, null, null, null, null, null)
                : body;

        if (request.keywords() == null || request.keywords().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "keywords is required to start a run.");
        }
        if (!request.useShardsOrDefault() && (request.location() == null || request.location().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "location is required unless useShards is true.");
        }

        sweepRunRepository.findRunning().ifPresent(running -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Run " + running.id() + " is already in progress. Wait for it to finish or cancel it "
                            + "(POST /api/runs/" + running.id() + "/cancel) before starting another.");
        });

        checkCircuitNotOpen();

        int hours = request.hours() != null ? request.hours() : DEFAULT_HOURS;
        String location = request.location() == null ? "" : request.location();
        SweepRunRequest sweepRunRequest = new SweepRunRequest(request.keywords(), location, hours,
                request.testModeOrDefault(), request.useShardsOrDefault(), request.shards(),
                request.pageCap());

        long runId = sweepService.startRun(sweepRunRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RunIdResponse(runId));
    }

    private void checkCircuitNotOpen() {
        CircuitSnapshot snapshot = circuitStateStore.load();
        if (snapshot.state() == CircuitState.OPEN && snapshot.openUntil() != null
                && clock.instant().isBefore(snapshot.openUntil())) {
            Duration remaining = Duration.between(clock.instant(), snapshot.openUntil());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LinkedIn requests are paused after repeated failures. This is a cooldown, not an "
                            + "error to clear — try again in about " + formatDuration(remaining) + ".");
        }
    }

    private static String formatDuration(Duration d) {
        long totalSeconds = Math.max(0, d.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    @GetMapping("/api/runs")
    public List<RunResponse> recentRuns(@RequestParam(name = "limit", required = false) Integer limit) {
        int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_RECENT_LIMIT;
        return sweepRunRepository.findRecent(effectiveLimit).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/api/runs/{id}")
    public RunResponse getRun(@PathVariable long id) {
        SweepRun run = sweepRunRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        return toResponse(run);
    }

    private RunResponse toResponse(SweepRun run) {
        Optional<SweepProgress> progress = sweepService.progress(run.id());
        return progress.map(p -> RunResponse.fromRunAndProgress(run, p)).orElseGet(() -> RunResponse.fromRun(run));
    }

    @PostMapping("/api/runs/{id}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable long id) {
        sweepRunRepository.findById(id).orElseThrow(() -> notFound(id));
        boolean cancelled = sweepService.cancel(id);
        if (!cancelled) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Run " + id + " is not currently running in this process, so it cannot be cancelled "
                            + "(it may already be finished).");
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value = "/api/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable long id) {
        if (sweepRunRepository.findById(id).isEmpty()) {
            throw notFound(id);
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        ScheduledFuture<?> future = sseScheduler.scheduleAtFixedRate(
                () -> pushProgress(id, emitter, futureRef), 0, SSE_POLL_MS, TimeUnit.MILLISECONDS);
        futureRef.set(future);

        emitter.onCompletion(() -> cancelQuietly(futureRef));
        emitter.onTimeout(() -> {
            cancelQuietly(futureRef);
            emitter.complete();
        });
        emitter.onError(e -> cancelQuietly(futureRef));

        return emitter;
    }

    private void pushProgress(long id, SseEmitter emitter, AtomicReference<ScheduledFuture<?>> futureRef) {
        try {
            RunResponse response;
            String status;
            Optional<SweepProgress> progress = sweepService.progress(id);
            if (progress.isPresent()) {
                SweepRun run = sweepRunRepository.findById(id).orElse(null);
                if (run == null) {
                    emitter.complete();
                    cancelQuietly(futureRef);
                    return;
                }
                response = RunResponse.fromRunAndProgress(run, progress.get());
                status = progress.get().status();
            } else {
                SweepRun run = sweepRunRepository.findById(id).orElse(null);
                if (run == null) {
                    emitter.complete();
                    cancelQuietly(futureRef);
                    return;
                }
                response = RunResponse.fromRun(run);
                status = run.status();
            }

            emitter.send(SseEmitter.event().name("progress").data(response, MediaType.APPLICATION_JSON));

            if (!"running".equals(status)) {
                emitter.complete();
                cancelQuietly(futureRef);
            }
        } catch (IOException | IllegalStateException e) {
            // Client disconnected mid-stream, or the emitter is already done — stop polling
            // quietly rather than spewing a stack trace on every subsequent tick.
            cancelQuietly(futureRef);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed/errored - nothing more to do
            }
        }
    }

    private static void cancelQuietly(AtomicReference<ScheduledFuture<?>> futureRef) {
        ScheduledFuture<?> future = futureRef.get();
        if (future != null) {
            future.cancel(false);
        }
    }

    private static ApiException notFound(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "No run found with id " + id + ".");
    }
}
