package com.ubaid.jobdash.store;

import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.store.adapter.JdbcCircuitStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcCircuitStateStoreTest extends AbstractStoreTest {

    @Test
    void loadReturnsSeedRowAsClosedInitialSnapshot() {
        JdbcCircuitStateStore store = new JdbcCircuitStateStore(circuitStateRepository);

        CircuitSnapshot snapshot = store.load();

        assertThat(snapshot.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(snapshot.consecutiveTrips()).isZero();
        assertThat(snapshot.softFailureCount()).isZero();
    }

    @Test
    void saveThenLoadRoundTripsThroughAllThreeStates() {
        JdbcCircuitStateStore store = new JdbcCircuitStateStore(circuitStateRepository);
        Instant openedAt = Instant.parse("2026-08-28T12:00:00Z");
        Instant openUntil = openedAt.plusSeconds(1800);

        store.save(new CircuitSnapshot(CircuitState.OPEN, 3, 0, openedAt, openUntil));
        CircuitSnapshot open = store.load();
        assertThat(open.state()).isEqualTo(CircuitState.OPEN);
        assertThat(open.consecutiveTrips()).isEqualTo(3);
        assertThat(open.openedAt()).isEqualTo(openedAt);
        assertThat(open.openUntil()).isEqualTo(openUntil);

        store.save(new CircuitSnapshot(CircuitState.HALF_OPEN, 3, 0, openedAt, openUntil));
        assertThat(store.load().state()).isEqualTo(CircuitState.HALF_OPEN);

        store.save(CircuitSnapshot.initial());
        CircuitSnapshot closed = store.load();
        assertThat(closed.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(closed.consecutiveTrips()).isZero();
    }
}
