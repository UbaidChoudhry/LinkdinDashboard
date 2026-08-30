package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
