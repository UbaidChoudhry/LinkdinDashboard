package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository for the {@code request_log} table, which backs the rolling request-rate budget.
 */
@Repository
public class RequestLogRepository {

    private final JdbcClient client;

    public RequestLogRepository(JdbcClient client) {
        this.client = client;
    }

    public int append(Long runId, Instant requestedAt, String url, Integer statusCode, String outcome, Integer bytes, Integer waitedMs) {
        return client.sql("""
                        insert into request_log (run_id, requested_at, url, status_code, outcome, bytes, waited_ms)
                        values (:runId, :requestedAt, :url, :statusCode, :outcome, :bytes, :waitedMs)
                        """)
                .param("runId", runId)
                .param("requestedAt", Timestamps.toText(requestedAt))
                .param("url", url)
                .param("statusCode", statusCode)
                .param("outcome", outcome)
                .param("bytes", bytes)
                .param("waitedMs", waitedMs)
                .update();
    }

    /** Number of requests logged at or after {@code since} — backs the rolling 24h request budget. */
    public long countSince(Instant since) {
        return client.sql("select count(*) from request_log where requested_at >= :since")
                .param("since", Timestamps.toText(since))
                .query(Long.class)
                .single();
    }
}
