package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SlugCatalogImportService} against a real, temporary SQLite database (genuine
 * Flyway migrations, same pattern as {@code store.AbstractStoreTest}) and local temp JSON files
 * only — no test here makes a network call. Per HANDOFF.md §1, these assert the positive outcome
 * (rows actually present with the right shape, or actually absent) rather than merely "it didn't
 * throw."
 */
class SlugCatalogImportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private AtsCompanyRepository repository;

    @BeforeEach
    void migrateFreshDatabase() {
        String url = "jdbc:sqlite:" + tempDir.resolve("ats-import-test.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.repository = new AtsCompanyRepository(JdbcClient.create(ds));
    }

    private SlugCatalogImportService service() {
        return new SlugCatalogImportService(repository, JsonMapper.builder().build(), CLOCK);
    }

    private Path writeJson(String content) throws IOException {
        Path file = tempDir.resolve("catalog.json");
        Files.writeString(file, content);
        return file;
    }

    private AtsCompany findOne(String ats, String slug) {
        List<AtsCompany> rows = repository.list(ats, slug, false, 10, 0);
        assertThat(rows).as(ats + "/" + slug).hasSize(1);
        return rows.getFirst();
    }

    @Test
    void onlyTheThreeSupportedAtsKeysAreImported() throws IOException {
        Path file = writeJson("""
                {"ats": {
                  "greenhouse": ["figma2"],
                  "lever": ["palantir2"],
                  "workday": ["nvidia2.wd5.myworkdayjobs.com"],
                  "ashby": ["someothercompany"],
                  "smartrecruiters": ["ignored"]
                }}
                """);

        SlugCatalogImportService.ImportResult result = service().importFrom(file.toString(), false);

        assertThat(result.byAts()).containsOnlyKeys("greenhouse", "lever", "workday");
        assertThat(repository.list(null, "someothercompany", false, 10, 0)).isEmpty();
        assertThat(repository.list(null, "ignored", false, 10, 0)).isEmpty();
        assertThat(findOne("greenhouse", "figma2")).isNotNull();
        assertThat(findOne("lever", "palantir2")).isNotNull();
        assertThat(findOne("workday", "nvidia2")).isNotNull();
    }

    @Test
    void workdayHostnameSplitsIntoSlugAndHostWithNullSite() throws IOException {
        Path file = writeJson("""
                {"ats": {"workday": ["examplecorp.wd5.myworkdayjobs.com"]}}
                """);

        service().importFrom(file.toString(), false);

        AtsCompany row = findOne("workday", "examplecorp");
        assertThat(row.host()).isEqualTo("examplecorp.wd5.myworkdayjobs.com");
        assertThat(row.site()).isNull();
    }

    @Test
    void importedRowsAreDisabledAndUnverified() throws IOException {
        Path file = writeJson("""
                {"ats": {"greenhouse": ["brandnewco"]}}
                """);

        service().importFrom(file.toString(), false);

        AtsCompany row = findOne("greenhouse", "brandnewco");
        assertThat(row.enabled()).isFalse();
        assertThat(row.status()).isEqualTo("unverified");
    }

    @Test
    void reimportingDoesNotReEnableOrOverwriteAnAlreadyEnabledCompany() throws IOException {
        // 'airbnb' is already seeded by V6 as enabled=1/status='active'/company='Airbnb'.
        AtsCompany before = findOne("greenhouse", "airbnb");
        assertThat(before.enabled()).isTrue();
        assertThat(before.status()).isEqualTo("active");

        Path file = writeJson("""
                {"ats": {"greenhouse": ["airbnb"]}}
                """);

        SlugCatalogImportService.ImportResult result = service().importFrom(file.toString(), false);

        assertThat(result.byAts().get("greenhouse").inserted()).isZero();
        assertThat(result.byAts().get("greenhouse").skippedExisting()).isEqualTo(1);

        AtsCompany after = findOne("greenhouse", "airbnb");
        assertThat(after.enabled()).isTrue();
        assertThat(after.status()).isEqualTo("active");
        assertThat(after.company()).isEqualTo("Airbnb");
        assertThat(after.id()).isEqualTo(before.id());
    }

    @Test
    void aMalformedEntryIsSkippedWithoutAbortingTheBatch() throws IOException {
        Path file = writeJson("""
                {"ats": {"greenhouse": ["freshco1", 42, "", "freshco2"]}}
                """);

        SlugCatalogImportService.ImportResult result = service().importFrom(file.toString(), false);

        assertThat(findOne("greenhouse", "freshco1")).isNotNull();
        assertThat(findOne("greenhouse", "freshco2")).isNotNull();
        assertThat(result.byAts().get("greenhouse").inserted()).isEqualTo(2);
        assertThat(result.byAts().get("greenhouse").malformed()).isEqualTo(2);
    }

    @Test
    void pruneDeadDeletesDeadRowsFirst() throws IOException {
        long id = findOne("greenhouse", "elastic").id();
        repository.recordFailure(id, Instant.parse("2026-01-01T00:00:00Z"), 1);
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo("dead");

        Path file = writeJson("""
                {"ats": {"greenhouse": []}}
                """);

        SlugCatalogImportService.ImportResult result = service().importFrom(file.toString(), true);

        assertThat(result.pruned()).isEqualTo(1);
        assertThat(repository.findById(id)).isEmpty();
    }
}
