package com.ubaid.jobdash.http;

/**
 * Narrow persistence port for circuit-breaker state. Backed by the {@code circuit_state}
 * table in production (wired by a later task); an in-memory implementation is used in tests.
 * Implementations need not be safe for concurrent use on their own — {@link CircuitBreaker}
 * serializes access with its own lock.
 */
public interface CircuitStateStore {

    /** Loads the current snapshot, or {@link CircuitSnapshot#initial()} if none has been saved. */
    CircuitSnapshot load();

    /** Persists a new snapshot, replacing whatever was there before. */
    void save(CircuitSnapshot snapshot);
}
