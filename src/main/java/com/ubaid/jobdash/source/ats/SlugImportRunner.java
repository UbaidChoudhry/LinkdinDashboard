package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.source.ats.SlugCatalogImportService.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot CLI entry point for the ATS slug-catalog import. Does nothing on a normal boot; only
 * when {@code ats.slugs.import-path} is set does it import that file/URL and then shut the
 * context down with an explicit exit code. It is a batch job, not a server start — mirrors
 * {@code salary.LcaImportRunner} exactly. The property is unset under {@code @SpringBootTest}, so
 * this never fires in tests.
 * <p>
 * All the importing lives in {@link SlugCatalogImportService#importFrom}, which is unit tested;
 * this class only reads config, logs the summary, and picks an exit code.
 */
@Component
public class SlugImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlugImportRunner.class);

    private final AtsProperties properties;
    private final SlugCatalogImportService importService;
    private final ApplicationContext applicationContext;

    public SlugImportRunner(AtsProperties properties, SlugCatalogImportService importService,
                             ApplicationContext applicationContext) {
        this.properties = properties;
        this.importService = importService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        AtsProperties.Slugs slugs = properties.slugs();
        String importPath = slugs == null ? null : slugs.importPath();
        if (importPath == null || importPath.isBlank()) {
            return;
        }

        int exitCode;
        try {
            ImportResult result = importService.importFrom(importPath.trim(), slugs.pruneDead());
            logSummary(result);
            exitCode = SpringApplication.exit(applicationContext, () -> 0);
        } catch (RuntimeException e) {
            log.error("slug catalog import failed for {}", importPath, e);
            exitCode = SpringApplication.exit(applicationContext, () -> 1);
        }
        System.exit(exitCode);
    }

    private void logSummary(ImportResult result) {
        log.info("slug catalog import finished: {} total inserted, {} pruned",
                result.totalInserted(), result.pruned());
        result.byAts().forEach((ats, r) -> log.info("  {} -> {} inserted, {} already existed, {} malformed",
                ats, r.inserted(), r.skippedExisting(), r.malformed()));
    }
}
