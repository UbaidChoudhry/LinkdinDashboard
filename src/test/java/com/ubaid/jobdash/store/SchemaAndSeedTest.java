package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that V1__init.sql / V2__seed.sql produce the expected schema and seed rows when
 * Flyway migrates a genuinely fresh SQLite file.
 */
class SchemaAndSeedTest extends AbstractStoreTest {

    @Test
    void allTablesExist() throws Exception {
        List<String> tables = namesOfType("table");
        assertThat(tables).contains(
                "job_listing", "sweep_run", "exclude_word", "company_blocklist",
                "filter_state", "request_log", "circuit_state",
                "salary_estimate", "lca_wage", "external_request_log");
    }

    @Test
    void bothPartialAndPlainIndexesExist() throws Exception {
        List<String> indexes = namesOfType("index");
        assertThat(indexes).contains("job_detail_queue", "job_browse", "request_log_time",
                "lca_wage_lookup", "external_request_log_time");
    }

    @Test
    void jobDetailQueueIndexIsPartial() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select sql from sqlite_master where name = 'job_detail_queue'")) {
            assertThat(rs.next()).isTrue();
            String sql = rs.getString(1).toLowerCase();
            assertThat(sql).contains("where");
            assertThat(sql).contains("detail_fetched_at is null");
            assertThat(sql).contains("filter_verdict = 'pass'");
        }
    }

    @Test
    void exactlyEightExcludeWordsSeeded() {
        List<String> words = excludeWordRepository.list();
        assertThat(words).hasSize(8);
        assertThat(words).extractingResultOf("toLowerCase").containsExactlyInAnyOrder(
                "senior", "sr", "staff", "principal", "lead", "manager", "director", "intern");
    }

    @Test
    void filterStateSeededAtVersionOne() {
        assertThat(filterStateRepository.currentVersion()).isEqualTo(1);
    }

    @Test
    void circuitStateSeededClosed() {
        assertThat(circuitStateRepository.read().state()).isEqualTo("closed");
    }

    /**
     * Migration-fidelity check for V5's create/copy/drop/rename rebuild of {@code job_listing}:
     * {@code job_detail_queue} and {@code job_browse} surviving the rebuild is already covered by
     * {@link #bothPartialAndPlainIndexesExist()} and {@link #jobDetailQueueIndexIsPartial()}
     * above (they run against the fully-migrated V1..V5 schema like every test in this class).
     * This test covers the other half: the new natural key, {@code unique (source,
     * source_job_id)}, is genuinely enforced - not just declared in the migration text.
     */
    @Test
    void sourceAndSourceJobIdUniqueConstraintIsEnforced() {
        long runId = sweepRunRepository.create(Instant.now(), "java", "remote", 24, false, null);
        JobCardInsert card = new JobCardInsert("lever", "ac978161-6f46-4f6b-ad9e-a258e642751c",
                "Engineer", "Acme", "Remote", Instant.now(),
                "https://jobs.lever.co/acme/ac978161", null, null);
        jobListingRepository.upsertAll(List.of(card), runId, Instant.now());

        // A direct insert bypassing the repository's upsert must still be rejected by the
        // schema itself - this is what proves the constraint, not just the application logic.
        assertThatThrownBy(() -> client.sql("""
                        insert into job_listing
                            (source, source_job_id, title, company, first_seen_at, last_seen_at,
                             last_seen_run_id, job_url)
                        values
                            ('lever', 'ac978161-6f46-4f6b-ad9e-a258e642751c', 'Duplicate', 'Acme',
                             :now, :now, :runId, 'https://jobs.lever.co/acme/dup')
                        """)
                .param("now", com.ubaid.jobdash.domain.Timestamps.toText(Instant.now()))
                .param("runId", runId)
                .update())
                .isInstanceOf(RuntimeException.class);

        Long rowCount = client.sql(
                        "select count(*) from job_listing where source_job_id = 'ac978161-6f46-4f6b-ad9e-a258e642751c'")
                .query(Long.class).single();
        assertThat(rowCount).isEqualTo(1);
    }

    private List<String> namesOfType(String type) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select name from sqlite_master where type = '" + type + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
