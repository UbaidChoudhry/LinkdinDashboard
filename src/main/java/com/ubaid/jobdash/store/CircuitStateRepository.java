package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Repository for the single-row {@code circuit_state} table (the request circuit breaker).
 */
@Repository
public class CircuitStateRepository {

    private final JdbcClient client;

    public CircuitStateRepository(JdbcClient client) {
        this.client = client;
    }

    public record CircuitState(
            String state,
            Instant openedAt,
            Instant reopenAfter,
            int consecutiveTrips,
            int softFailures,
            String lastReason
    ) {
    }

    public CircuitState read() {
        return client.sql("select * from circuit_state where id = 1")
                .query(CircuitStateRepository::mapRow)
                .single();
    }

    public int write(String state, Instant openedAt, Instant reopenAfter, int consecutiveTrips, int softFailures, String lastReason) {
        return client.sql("""
                        update circuit_state
                        set state = :state,
                            opened_at = :openedAt,
                            reopen_after = :reopenAfter,
                            consecutive_trips = :consecutiveTrips,
                            soft_failures = :softFailures,
                            last_reason = :lastReason
                        where id = 1
                        """)
                .param("state", state)
                .param("openedAt", Timestamps.toText(openedAt))
                .param("reopenAfter", Timestamps.toText(reopenAfter))
                .param("consecutiveTrips", consecutiveTrips)
                .param("softFailures", softFailures)
                .param("lastReason", lastReason)
                .update();
    }

    static CircuitState mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CircuitState(
                rs.getString("state"),
                Timestamps.parse(rs.getString("opened_at")),
                Timestamps.parse(rs.getString("reopen_after")),
                rs.getInt("consecutive_trips"),
                rs.getInt("soft_failures"),
                rs.getString("last_reason")
        );
    }
}
