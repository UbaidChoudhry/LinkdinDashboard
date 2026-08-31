package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.salary.LcaImportService.ImportResult;
import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.LcaWageRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LcaImportServiceTest extends AbstractStoreTest {

    private static final List<String> HEADERS = List.of(
            "CASE_STATUS", "DECISION_DATE", "EMPLOYER_NAME", "SOC_CODE",
            "WORKSITE_STATE", "WAGE_RATE_OF_PAY_FROM", "WAGE_RATE_OF_PAY_TO", "WAGE_UNIT_OF_PAY");

    private LcaImportService newService() {
        SalaryProperties props = new SalaryProperties(
                true, Duration.ofDays(90), Duration.ofDays(1095),
                null, null, null, null, null, new SalaryProperties.Lca("", true));
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        return new LcaImportService(new XlsxStreamReader(), lcaWageRepository, props, clock);
    }

    private Path fixture() {
        List<List<String>> rows = List.of(
                row("Certified", "2025-06-01", "Acme Corp", "15-1252.00", "CA", "180000", "", "Year"),
                row("Certified", "2025-07-01", "Acme Corp", "15-1252.00", "CA", "200000", "", "Year"),
                row("Denied", "2025-06-01", "Acme Corp", "15-1252.00", "CA", "999999", "", "Year"),
                row("Certified", "2025-06-01", "Acme Corp", "15-1252.00", "NY", "90", "", "Hour"),
                row("Certified", "2020-01-01", "Acme Corp", "15-1252.00", "CA", "150000", "", "Year"),
                row("Certified", "2025-06-01", "", "15-1252.00", "CA", "150000", "", "Year"),
                row("Certified", "2025-06-01", "Beta LLC", "15-1252.00", "TX", "50", "", "Hour"),
                row("Certified", "2025-06-01", "Gamma Inc", "15-9999.00", "WA", "5", "", "Hour"));
        return XlsxFixtures.write(tempDir.resolve("lca.xlsx"), HEADERS, rows);
    }

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void importsCertifiedRowsAndAggregatesPercentiles() {
        ImportResult result = newService().importFrom(fixture());

        assertThat(result.rowsRead()).isEqualTo(8);
        assertThat(result.rowsKept()).isEqualTo(4); // rows 1, 2, 4, 7
        assertThat(result.groupsWritten()).isEqualTo(5);
        assertThat(result.oldestKept()).isEqualTo("2025-06-01");
        assertThat(result.newestKept()).isEqualTo("2025-07-01");

        LcaWageRow ca = lcaWageRepository.lookup("acme", List.of("15-1252"), "CA").orElseThrow();
        assertThat(ca.state()).isEqualTo("CA");
        assertThat(ca.sampleCount()).isEqualTo(2);
        assertThat(ca.wageP50()).isEqualTo(180000.0);
        assertThat(ca.wageP75()).isEqualTo(200000.0);
        assertThat(ca.wageMax()).isEqualTo(200000.0);
        // employer_display carries the raw EMPLOYER_NAME, not the normalized key.
        assertThat(ca.employerDisplay()).isEqualTo("Acme Corp");
        assertThat(ca.employerKey()).isEqualTo("acme");

        LcaWageRow ny = lcaWageRepository.lookup("acme", List.of("15-1252"), "NY").orElseThrow();
        assertThat(ny.wageP50()).isEqualTo(90.0 * 2080);

        // National rollup covers CA + NY offers.
        LcaWageRow national = lcaWageRepository.lookup("acme", List.of("15-1252"), "ZZ").orElseThrow();
        assertThat(national.state()).isEmpty();
        assertThat(national.sampleCount()).isEqualTo(3);
        assertThat(national.wageP50()).isEqualTo(90.0 * 2080);

        LcaWageRow beta = lcaWageRepository.lookup("beta", List.of("15-1252"), "TX").orElseThrow();
        assertThat(beta.wageP50()).isEqualTo(50.0 * 2080);

        // Gamma's only row annualizes to 10400 (< 15000 floor) -> discarded entirely.
        assertThat(lcaWageRepository.lookup("gamma", List.of("15-9999"), "WA")).isEmpty();
    }

    // ---- batch import over a directory -------------------------------------------------------

    /** A directory fixture: {@code good} imports rows, {@code empty} parses but keeps none. */
    private Path dirWith(String... names) throws Exception {
        Path dir = java.nio.file.Files.createDirectories(tempDir.resolve("batch"));
        for (String name : names) {
            List<List<String>> rows = name.startsWith("empty")
                    // Every row Denied -> parses fine, contributes nothing.
                    ? List.of(row("Denied", "2025-06-01", "Acme Corp", "15-1252.00", "CA", "180000", "", "Year"))
                    : List.of(row("Certified", "2025-06-01", "Acme Corp", "15-1252.00", "CA", "180000", "", "Year"));
            XlsxFixtures.write(dir.resolve(name), HEADERS, rows);
        }
        return dir;
    }

    @Test
    void importsEverySpreadsheetInADirectoryAndDeletesTheOnesThatImported() throws Exception {
        Path dir = dirWith("good_a.xlsx", "good_b.xlsx");

        LcaImportService.BatchResult batch = newService().importAll(dir, true);

        assertThat(batch.outcomes()).hasSize(2);
        assertThat(batch.countOf(LcaImportService.FileStatus.IMPORTED_AND_DELETED)).isEqualTo(2);
        assertThat(batch.anyFailed()).isFalse();
        // The positive outcome that matters: the files are actually gone from disk.
        assertThat(java.nio.file.Files.list(dir)).isEmpty();
        assertThat(lcaWageRepository.lookup("acme", List.of("15-1252"), "CA")).isPresent();
    }

    @Test
    void keepsFilesWhenDeletionIsDisabled() throws Exception {
        Path dir = dirWith("good_a.xlsx");

        LcaImportService.BatchResult batch = newService().importAll(dir, false);

        assertThat(batch.countOf(LcaImportService.FileStatus.IMPORTED_KEPT)).isEqualTo(1);
        assertThat(dir.resolve("good_a.xlsx")).exists();
    }

    /**
     * A spreadsheet that parses but contributes nothing is far more likely to mean a changed
     * column layout than an empty quarter — deleting an 80 MB download on that signal would
     * destroy the only evidence. It must survive.
     */
    @Test
    void neverDeletesAFileThatContributedNoRows() throws Exception {
        Path dir = dirWith("empty_a.xlsx", "good_b.xlsx");

        LcaImportService.BatchResult batch = newService().importAll(dir, true);

        assertThat(batch.countOf(LcaImportService.FileStatus.NOTHING_IMPORTED)).isEqualTo(1);
        assertThat(batch.countOf(LcaImportService.FileStatus.IMPORTED_AND_DELETED)).isEqualTo(1);
        assertThat(dir.resolve("empty_a.xlsx")).exists();
        assertThat(dir.resolve("good_b.xlsx")).doesNotExist();
    }

    /** One corrupt file must not stop the rest of the batch, and must not be deleted. */
    @Test
    void aFailingFileIsReportedAndKeptWhileTheBatchContinues() throws Exception {
        Path dir = dirWith("good_b.xlsx");
        Path broken = dir.resolve("aaa_broken.xlsx"); // sorts first, so the batch must carry on past it
        java.nio.file.Files.writeString(broken, "this is not a spreadsheet");

        LcaImportService.BatchResult batch = newService().importAll(dir, true);

        assertThat(batch.outcomes()).hasSize(2);
        assertThat(batch.anyFailed()).isTrue();
        assertThat(batch.countOf(LcaImportService.FileStatus.FAILED)).isEqualTo(1);
        assertThat(broken).exists();
        // The healthy file after it was still imported and cleaned up.
        assertThat(batch.countOf(LcaImportService.FileStatus.IMPORTED_AND_DELETED)).isEqualTo(1);
        assertThat(dir.resolve("good_b.xlsx")).doesNotExist();
    }

    @Test
    void nonSpreadsheetsAndExcelLockFilesAreIgnored() throws Exception {
        Path dir = dirWith("good_a.xlsx");
        java.nio.file.Files.writeString(dir.resolve("notes.txt"), "hello");
        java.nio.file.Files.writeString(dir.resolve("~$good_a.xlsx"), "lock");

        LcaImportService.BatchResult batch = newService().importAll(dir, true);

        assertThat(batch.outcomes()).hasSize(1);
        assertThat(dir.resolve("notes.txt")).exists();
        assertThat(dir.resolve("~$good_a.xlsx")).exists();
    }

    @Test
    void aSingleFilePathStillWorks() {
        LcaImportService.BatchResult batch = newService().importAll(fixture(), false);

        assertThat(batch.outcomes()).hasSize(1);
        assertThat(batch.countOf(LcaImportService.FileStatus.IMPORTED_KEPT)).isEqualTo(1);
    }
}
