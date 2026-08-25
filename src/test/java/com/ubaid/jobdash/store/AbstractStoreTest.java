package com.ubaid.jobdash.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Base class for repository/migration integration tests. Each test class (each subclass
 * instance, i.e. each test method with JUnit's default per-method lifecycle) gets a brand new
 * SQLite file under a JUnit-managed {@code @TempDir}, with Flyway migrations genuinely applied
 * against it — no shared state, no mocking of the database.
 */
abstract class AbstractStoreTest {

    @TempDir
    Path tempDir;

    DataSource dataSource;
    JdbcClient client;

    JobListingRepository jobListingRepository;
    SweepRunRepository sweepRunRepository;
    ExcludeWordRepository excludeWordRepository;
    CompanyBlocklistRepository companyBlocklistRepository;
    FilterStateRepository filterStateRepository;
    RequestLogRepository requestLogRepository;
    CircuitStateRepository circuitStateRepository;

    @BeforeEach
    void migrateFreshDatabase() {
        String url = "jdbc:sqlite:" + tempDir.resolve("jobdash-test.db");

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        this.dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.client = JdbcClient.create(dataSource);
        this.jobListingRepository = new JobListingRepository(client);
        this.sweepRunRepository = new SweepRunRepository(client);
        this.excludeWordRepository = new ExcludeWordRepository(client);
        this.companyBlocklistRepository = new CompanyBlocklistRepository(client);
        this.filterStateRepository = new FilterStateRepository(client);
        this.requestLogRepository = new RequestLogRepository(client);
        this.circuitStateRepository = new CircuitStateRepository(client);
    }
}
