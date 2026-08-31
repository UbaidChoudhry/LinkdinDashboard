package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.salary.LcaImportService.BatchResult;
import com.ubaid.jobdash.salary.LcaImportService.FileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * One-shot CLI entry point for the LCA import. Does nothing on a normal boot; only when
 * {@code salary.lca.import-path} (env {@code LCA_IMPORT_PATH}) is set does it import that file —
 * or every {@code .xlsx} in that directory — and then shut the context down with an explicit
 * exit code. It is a batch job, not a server start. The property is unset under
 * {@code @SpringBootTest}, so this never fires in tests.
 *
 * <p>All the importing and deleting lives in {@link LcaImportService#importAll}, which is unit
 * tested; this class only reads config, logs the summary, and picks an exit code.
 */
@Component
public class LcaImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LcaImportRunner.class);

    private final SalaryProperties properties;
    private final LcaImportService importService;
    private final ApplicationContext applicationContext;

    public LcaImportRunner(SalaryProperties properties, LcaImportService importService,
                           ApplicationContext applicationContext) {
        this.properties = properties;
        this.importService = importService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        SalaryProperties.Lca lca = properties.lca();
        String importPath = lca == null ? null : lca.importPath();
        if (importPath == null || importPath.isBlank()) {
            return;
        }

        Path target = Path.of(importPath.trim());
        int exitCode;
        try {
            BatchResult batch = importService.importAll(target, lca.deleteAfterImport());
            logSummary(batch);
            exitCode = SpringApplication.exit(applicationContext, () -> batch.anyFailed() ? 1 : 0);
        } catch (RuntimeException e) {
            log.error("LCA import failed for {}", target, e);
            exitCode = SpringApplication.exit(applicationContext, () -> 1);
        }
        System.exit(exitCode);
    }

    private void logSummary(BatchResult batch) {
        log.info("LCA import finished: {} file(s) - {} imported+deleted, {} imported+kept, "
                        + "{} contributed nothing, {} failed",
                batch.outcomes().size(),
                batch.countOf(FileStatus.IMPORTED_AND_DELETED),
                batch.countOf(FileStatus.IMPORTED_KEPT),
                batch.countOf(FileStatus.NOTHING_IMPORTED),
                batch.countOf(FileStatus.FAILED));
        batch.outcomes().forEach(o -> log.info("  {} -> {}{}",
                o.file().getFileName(), o.status(),
                o.result() == null ? "" : " (" + o.result().rowsKept() + " rows kept, "
                        + o.result().groupsWritten() + " groups)"));
    }
}
