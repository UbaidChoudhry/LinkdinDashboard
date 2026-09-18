package com.ubaid.jobdash.apply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Closes out apply batches left {@code running} when the application last stopped - the apply
 * counterpart of {@code sweep.OrphanedRunReaper}, for the same reason.
 * <p>
 * A batch lives on a virtual thread inside one process. Restart the backend mid-batch (batch 9,
 * 2026-09-23: 42 jobs, restarted after 3) and the thread is gone but the {@code apply_batch} row
 * keeps {@code status='running'} forever. That wedges the feature: {@code POST /api/applications}
 * answers 409 "already in progress" to every click, the Results tab re-attaches to the dead batch
 * and shows "Applying 3/42" indefinitely, and Cancel is a no-op because the batch is not in this
 * process's registry. Reaping at startup is safe because nothing can still be running when the
 * process has only just booted.
 */
@Component
public class OrphanedApplyBatchReaper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedApplyBatchReaper.class);

    private final ApplyOrchestrator applyOrchestrator;

    public OrphanedApplyBatchReaper(ApplyOrchestrator applyOrchestrator) {
        this.applyOrchestrator = applyOrchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        int reaped = applyOrchestrator.reapOrphanedBatches(ApplyOrchestrator.INTERRUPTED,
                "interrupted: the backend restarted while this batch was running");
        if (reaped > 0) {
            log.info("marked {} unfinished apply batch(es) as '{}' - orphaned when the application last stopped",
                    reaped, ApplyOrchestrator.INTERRUPTED);
        }
    }
}
