package com.ubaid.jobdash.http.support;

import com.ubaid.jobdash.http.CircuitSnapshot;
import com.ubaid.jobdash.http.CircuitStateStore;

/** In-memory {@link CircuitStateStore} for tests. Shared across instances to simulate a restart. */
public final class InMemoryCircuitStateStore implements CircuitStateStore {

    private CircuitSnapshot snapshot = CircuitSnapshot.initial();

    @Override
    public synchronized CircuitSnapshot load() {
        return snapshot;
    }

    @Override
    public synchronized void save(CircuitSnapshot snapshot) {
        this.snapshot = snapshot;
    }
}
