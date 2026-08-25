package com.ubaid.jobdash.store;

import com.ubaid.jobdash.http.RequestRecord;
import com.ubaid.jobdash.http.ResponseOutcome;
import com.ubaid.jobdash.store.adapter.JdbcRequestBudgetStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRequestBudgetStoreTest extends AbstractStoreTest {

    @Test
    void recordsThroughToRequestLogAndCountsCorrectly() {
        JdbcRequestBudgetStore store = new JdbcRequestBudgetStore(requestLogRepository);
        Instant base = Instant.parse("2026-08-28T12:00:00Z");

        store.record(new RequestRecord(base.minus(2, ChronoUnit.HOURS), "https://x/1", 200, 100, ResponseOutcome.OK));
        store.record(new RequestRecord(base, "https://x/2", 429, 5000, ResponseOutcome.BLOCKED));
        store.record(new RequestRecord(base.plus(1, ChronoUnit.HOURS), "https://x/3", -1, 0, ResponseOutcome.BLOCKED));

        assertThat(store.countSince(base)).isEqualTo(2);
        assertThat(store.countSince(base.minus(3, ChronoUnit.HOURS))).isEqualTo(3);

        // -1 (no HTTP response) is narrowed to null status code, not persisted as -1.
        assertThat(requestLogRepository.countSince(base.plus(1, ChronoUnit.HOURS))).isEqualTo(1L);
    }
}
