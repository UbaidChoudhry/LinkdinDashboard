package com.ubaid.jobdash.web;

import com.ubaid.jobdash.store.DataRepository;
import com.ubaid.jobdash.store.DatabaseFileLocator;
import com.ubaid.jobdash.store.RequestLogRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.web.dto.ClearJobDataResponse;
import com.ubaid.jobdash.web.dto.DataStatsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

/**
 * The Data tab's backend: how big the database is, what's in it, and a way to clear job
 * results and run history when you want a fresh start. See {@link DataRepository} for exactly
 * what "clear" does and does not remove.
 */
@RestController
public class DataController {

    private final DataRepository dataRepository;
    private final DatabaseFileLocator databaseFileLocator;
    private final RequestLogRepository requestLogRepository;
    private final SweepRunRepository sweepRunRepository;
    private final Clock clock;

    public DataController(DataRepository dataRepository, DatabaseFileLocator databaseFileLocator,
                           RequestLogRepository requestLogRepository, SweepRunRepository sweepRunRepository,
                           Clock clock) {
        this.dataRepository = dataRepository;
        this.databaseFileLocator = databaseFileLocator;
        this.requestLogRepository = requestLogRepository;
        this.sweepRunRepository = sweepRunRepository;
        this.clock = clock;
    }

    @GetMapping("/api/data/stats")
    public DataStatsResponse stats() {
        long requestsLast24h = requestLogRepository.countSince(clock.instant().minus(Duration.ofHours(24)));
        return DataStatsResponse.of(
                databaseFileLocator.sizeBytes(),
                dataRepository.jobStats(),
                dataRepository.countRuns(),
                dataRepository.countRequestLogEntries(),
                requestsLast24h,
                dataRepository.countExcludeWords(),
                dataRepository.countBlockedCompanies()
        );
    }

    @PostMapping("/api/data/clear")
    public ClearJobDataResponse clear() {
        sweepRunRepository.findRunning().ifPresent(running -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Run " + running.id() + " is still in progress. Wait for it to finish or cancel it "
                            + "(POST /api/runs/" + running.id() + "/cancel) before clearing the database.");
        });

        DataRepository.ClearResult result = dataRepository.clearJobResults();
        dataRepository.vacuum();
        return new ClearJobDataResponse(result.jobsCleared(), result.runsCleared(), databaseFileLocator.sizeBytes());
    }
}
