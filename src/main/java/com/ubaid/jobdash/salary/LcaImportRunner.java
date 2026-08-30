package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.salary.LcaImportService.ImportResult;
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
 * {@code salary.lca.import-file} (env {@code LCA_IMPORT_FILE}) is set does it run the import
 * and then shut the context down with an explicit exit code — it is a batch job, not a server
 * start. The property is unset under {@code @SpringBootTest}, so this never fires in tests.
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
        String importFile = properties.lca() == null ? null : properties.lca().importFile();
        if (importFile == null || importFile.isBlank()) {
            return;
        }

        Path path = Path.of(importFile.trim());
        int exitCode;
        try {
            ImportResult result = importService.importFrom(path);
            log.info("LCA import finished: {}", result);
            exitCode = SpringApplication.exit(applicationContext, () -> 0);
        } catch (RuntimeException e) {
            log.error("LCA import failed for {}", path, e);
            exitCode = SpringApplication.exit(applicationContext, () -> 1);
        }
        System.exit(exitCode);
    }
}
