package com.ubaid.jobdash.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for the single-row {@code filter_state} table, which tracks the current
 * exclusion-word/blocklist filter version used to detect stale {@code job_listing} rows
 * that need re-evaluation.
 */
@Repository
public class FilterStateRepository {

    private final JdbcClient client;

    public FilterStateRepository(JdbcClient client) {
        this.client = client;
    }

    public int currentVersion() {
        return client.sql("select filter_version from filter_state where id = 1")
                .query(Integer.class)
                .single();
    }

    /** Atomically bumps the filter version and returns the new value. */
    @Transactional
    public int bumpVersion() {
        client.sql("update filter_state set filter_version = filter_version + 1 where id = 1")
                .update();
        return currentVersion();
    }
}
