package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.store.SweepRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Closes out runs that were left open when the application last stopped.
 *
 * <p>A run lives on a virtual thread inside one process. If that process is killed — a crash, a
 * Ctrl-C, or an ordinary restart during development — the thread dies but its {@code sweep_run}
 * row keeps {@code status='running'} and a null {@code finished_at} forever.
 *
 * <p>That row is not merely untidy, it wedges the application: {@code POST /api/runs} refuses to
 * start a second concurrent run, and {@code POST /api/runs/{id}/cancel} refuses too, because the
 * run is not in this process's registry. The user is left unable to start a run or clear the one
 * blocking them, with no way out except editing the database by hand.
 *
 * <p>Reaping at startup is safe precisely because of the single-process rule: nothing can still
 * be running when the application has only just booted.
 */
@Component
public class OrphanedRunReaper implements ApplicationRunner {

    /** Terminal status for a run the process never got to finish. See {@code utils/runStatus.ts}. */
    static final String INTERRUPTED = "interrupted";

    private static final Logger log = LoggerFactory.getLogger(OrphanedRunReaper.class);

    private final SweepRunRepository sweepRunRepository;
    private final Clock clock;

    public OrphanedRunReaper(SweepRunRepository sweepRunRepository, Clock clock) {
        this.sweepRunRepository = sweepRunRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int reaped = sweepRunRepository.finishAllUnfinished(clock.instant(), INTERRUPTED);
        if (reaped > 0) {
            log.info("marked {} unfinished run(s) as '{}' - they were orphaned when the application "
                    + "last stopped and could never have resumed", reaped, INTERRUPTED);
        }
    }
}
