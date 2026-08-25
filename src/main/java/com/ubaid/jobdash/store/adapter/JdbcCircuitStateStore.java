package com.ubaid.jobdash.store.adapter;

import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitState;
import com.ubaid.jobdash.http.CircuitStateStore;
import com.ubaid.jobdash.store.CircuitStateRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * JDBC-backed {@link CircuitStateStore}, delegating to the single-row {@code circuit_state}
 * table via {@link CircuitStateRepository}. Converts between {@link CircuitSnapshot} (T4's
 * in-process view, seen only by {@link com.ubaid.jobdash.http.CircuitBreaker}) and
 * {@link CircuitStateRepository.CircuitState} (the persisted row shape, whose {@code state}
 * column stores lower-case text such as {@code "closed"} / {@code "open"} / {@code "half_open"}
 * — see {@code V2__seed.sql}).
 */
@Component
public class JdbcCircuitStateStore implements CircuitStateStore {

    private final CircuitStateRepository circuitStateRepository;

    public JdbcCircuitStateStore(CircuitStateRepository circuitStateRepository) {
        this.circuitStateRepository = circuitStateRepository;
    }

    @Override
    public CircuitSnapshot load() {
        CircuitStateRepository.CircuitState row = circuitStateRepository.read();
        CircuitState state = CircuitState.valueOf(row.state().toUpperCase(Locale.ROOT));
        return new CircuitSnapshot(state, row.consecutiveTrips(), row.softFailures(),
                row.openedAt(), row.reopenAfter());
    }

    @Override
    public void save(CircuitSnapshot snapshot) {
        // CircuitSnapshot has no lastReason field, so this store never populates that column.
        // save() is documented to fully replace the persisted snapshot, so that's intentional.
        circuitStateRepository.write(snapshot.state().name().toLowerCase(Locale.ROOT),
                snapshot.openedAt(), snapshot.openUntil(), snapshot.consecutiveTrips(),
                snapshot.softFailureCount(), null);
    }
}
