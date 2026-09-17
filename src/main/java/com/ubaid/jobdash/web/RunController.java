package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.http.CircuitStateStore;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.sweep.RunOrchestrator;
import com.ubaid.jobdash.sweep.RunProgressRegistry;
import com.ubaid.jobdash.sweep.SweepProgress;
import com.ubaid.jobdash.sweep.SweepRunRequest;
import com.ubaid.jobdash.web.dto.CooldownResponse;
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
 * collection work lives in {@link RunOrchestrator} (which dispatches to {@code SweepService} for
 * LinkedIn or {@code AtsSweepService} for Greenhouse/Lever/Workday); this controller only
 * translates HTTP in and out of it, plus the request validation that has to happen before either
 * path starts (source selection, the circuit-breaker guard).
 */
@RestController
public class RunController {

    private static final int DEFAULT_HOURS = 24;
    private static final int DEFAULT_RECENT_LIMIT = 20;
    /** How often the SSE stream polls the progress registry and pushes an event. */
    private static final long SSE_POLL_MS = 750;
    /** Generous emitter timeout so a slow/long run's stream is never dropped early. */
    private static final long SSE_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final List<String> VALID_SOURCES = List.of("linkedin", "greenhouse", "lever", "workday");
    /** Statuses a run passes through before reaching a terminal one. Mirror of the frontend's IN_FLIGHT_STATUSES. */
    private static final List<String> IN_FLIGHT_STATUSES =
            List.of("running", "fetching_details", "scanning", "matching");

    private final RunOrchestrator runOrchestrator;
    private final RunProgressRegistry runProgressRegistry;
    private final SweepRunRepository sweepRunRepository;
    private final JobListingRepository jobListingRepository;
    private final CircuitStateStore circuitStateStore;
    private final Clock clock;
    private final ScheduledExecutorService sseScheduler;

    public RunController(RunOrchestrator runOrchestrator, RunProgressRegistry runProgressRegistry,
                          SweepRunRepository sweepRunRepository, JobListingRepository jobListingRepository,
                          CircuitStateStore circuitStateStore, Clock clock, ScheduledExecutorService sseScheduler) {
        this.runOrchestrator = runOrchestrator;
        this.runProgressRegistry = runProgressRegistry;
        this.sweepRunRepository = sweepRunRepository;
        this.jobListingRepository = jobListingRepository;
        this.circuitStateStore = circuitStateStore;
        this.clock = clock;
        this.sseScheduler = sseScheduler;
    }

    @PostMapping("/api/runs")
    public ResponseEntity<RunIdResponse> createRun(@RequestBody(required = false) CreateRunRequest body) {
        CreateRunRequest request = body == null
                ? new CreateRunRequest(null, null, null, null, null, null, null, null, null, null)
                : body;

        if (request.keywords() == null || request.keywords().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "keywords is required to start a run.");
        }
        List<String> sources = request.sourcesOrDefault();
        validateSources(sources);

        // location is only MANDATORY for LinkedIn: SweepQueryBuilder has to put it in the query
        // string, so a blank one would search the wrong thing. For the ATS boards it is just an
        // optional local filter on what the board already returned - a blank location there
        // legitimately means "anywhere", and demanding one would make a perfectly valid
        // nationwide ATS run impossible to start.
        if (sources.contains("linkedin") && !request.useShardsOrDefault()
                && (request.location() == null || request.location().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "location is required for a LinkedIn run unless useShards is true.");
        }

        sweepRunRepository.findRunning().ifPresent(running -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Run " + running.id() + " is already in progress. Wait for it to finish or cancel it "
                            + "(POST /api/runs/" + running.id() + "/cancel) before starting another.");
        });

        // The breaker only guards LinkedIn traffic - a pure ATS run must never be blocked by it.
        if (sources.contains("linkedin")) {
            checkCircuitNotOpen();
        }

        int hours = request.hours() != null ? request.hours() : DEFAULT_HOURS;
        String location = request.location() == null ? "" : request.location();
        SweepRunRequest sweepRunRequest = new SweepRunRequest(request.keywords(), location, hours,
                request.testModeOrDefault(), request.useShardsOrDefault(), request.shards(),
                request.pageCap());

        long runId = runOrchestrator.startRun(sweepRunRequest, sources, request.resumeId(),
                request.usOnlyOrDefault());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RunIdResponse(runId));
    }

    private static void validateSources(List<String> sources) {
        for (String source : sources) {
            if (!VALID_SOURCES.contains(source)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown source '" + source + "'. Valid values are: " + String.join(", ", VALID_SOURCES) + ".");
            }
        }
        // Any mix is allowed, LinkedIn included: RunOrchestrator runs the LinkedIn phase and the
        // ATS phase back to back on one run, each under its own budget.
    }

    private void checkCircuitNotOpen() {
        CooldownResponse cooldown = currentCooldown();
        if (cooldown.active()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LinkedIn requests are paused after repeated failures. This is a cooldown, not an "
                            + "error to clear — try again in about "
                            + formatDuration(Duration.ofSeconds(cooldown.remainingSeconds())) + ".");
        }
    }

    /**
     * The LinkedIn cooldown, for the run panel's countdown. Declared before {@code /api/runs/{id}}
     * only for readability - Spring picks the literal path over the variable one regardless.
     */
    @GetMapping("/api/runs/cooldown")
    public CooldownResponse cooldown() {
        return currentCooldown();
    }

    private CooldownResponse currentCooldown() {
        CircuitSnapshot snapshot = circuitStateStore.load();
        if (snapshot.state() == CircuitState.OPEN && snapshot.openUntil() != null
                && clock.instant().isBefore(snapshot.openUntil())) {
            Duration remaining = Duration.between(clock.instant(), snapshot.openUntil());
            return new CooldownResponse(true, snapshot.openUntil(), Math.max(0, remaining.getSeconds()));
        }
        return CooldownResponse.inactive();
    }

    /**
     * Re-opens a finished run for the phases a LinkedIn cooldown, cap or budget cut short:
     * fetching the descriptions of the rows it collected but never read, then the AI scan. The
     * search itself is not repeated. Same guards as starting a run - one run at a time, and no
     * LinkedIn traffic while the breaker is open - because it spends the same budget.
     */
    @PostMapping("/api/runs/{id}/resume")
    public ResponseEntity<RunIdResponse> resumeRun(@PathVariable long id) {
        SweepRun run = sweepRunRepository.findById(id).orElseThrow(() -> notFound(id));
        sweepRunRepository.findRunning().ifPresent(running -> {
            throw new ApiException(HttpStatus.CONFLICT, running.id() == id
                    ? "Run " + id + " is still in progress; there is nothing to resume yet."
                    : "Run " + running.id() + " is already in progress. Wait for it to finish or cancel it "
                            + "before resuming run " + id + ".");
        });
        if (run.sources() == null || run.sources().contains("linkedin")) {
            checkCircuitNotOpen();
        }
        runOrchestrator.resumeRun(id);
        return ResponseEntity.accepted().body(new RunIdResponse(id));
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
        Optional<SweepProgress> progress = runProgressRegistry.progress(run.id());
        return withRetryHint(run,
                progress.map(p -> RunResponse.fromRunAndProgress(run, p)).orElseGet(() -> RunResponse.fromRun(run)));
    }

    /**
     * A finished run carries how many of its LinkedIn rows still lack a description, so the
     * panel can offer "Retry" with a real number. Only for finished runs: while in flight the
     * count is changing under the detail phase, and the SSE poll would pay for it every tick.
     */
    private RunResponse withRetryHint(SweepRun run, RunResponse response) {
        if (run.finishedAt() == null) {
            return response;
        }
        return response.withUnfetchedDescriptions(jobListingRepository.countUnfetchedDescriptions(run.id()));
    }

    @PostMapping("/api/runs/{id}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable long id) {
        sweepRunRepository.findById(id).orElseThrow(() -> notFound(id));
        boolean cancelled = runProgressRegistry.cancel(id);
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
            SweepRun run = sweepRunRepository.findById(id).orElse(null);
            if (run == null) {
                emitter.complete();
                cancelQuietly(futureRef);
                return;
            }

            RunResponse response;
            String status;
            Optional<SweepProgress> progress = runProgressRegistry.progress(id);
            if (progress.isPresent()) {
                response = RunResponse.fromRunAndProgress(run, progress.get());
                status = progress.get().status();
            } else {
                response = RunResponse.fromRun(run);
                status = run.status();
            }

            emitter.send(SseEmitter.event().name("progress")
                    .data(withRetryHint(run, response), MediaType.APPLICATION_JSON));

            // "fetching_details" (a LinkedIn run reading job descriptions) and "scanning" (the AI
            // resume scan) are the transient phases after collection - still in flight, not
            // terminal statuses, so the stream stays open through them.
            //
            // A status outside IN_FLIGHT_STATUSES is not proof the run is over: SweepService's
            // collect() publishes the LinkedIn phase's own outcome (e.g. "ok") into the shared
            // progress registry the instant collection ends, before the next phase (detail fetch,
            // then the scan) publishes its own in-flight status a moment later. This poll runs
            // every SSE_POLL_MS regardless of that transition, so it can land squarely inside the
            // gap and read "ok" while the run is still very much in progress. finishedAt is set
            // exactly once, by RunOrchestrator.finishRun(), which persists it to the row strictly
            // after every phase - including the AI scan - has actually completed, and it does so
            // before republishing the matching terminal status into the registry - so pairing the
            // two here is race-free. Completing the emitter on the bare status was closing this
            // SSE stream for good mid-scan; the frontend's isRunFinished() is the mirror of this.
            if (!IN_FLIGHT_STATUSES.contains(status) && run.finishedAt() != null) {
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
