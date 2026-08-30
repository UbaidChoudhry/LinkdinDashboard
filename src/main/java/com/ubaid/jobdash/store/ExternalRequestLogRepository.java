package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository for the {@code external_request_log} table, which backs the salary rate limiter's
 * rolling per-source daily cap. One row per outbound call to an external salary API.
 */
@Repository
public class ExternalRequestLogRepository {

    private final JdbcClient client;

    public ExternalRequestLogRepository(JdbcClient client) {
        this.client = client;
    }

    /** Records one outbound call. {@code source} is {@code 'adzuna'} or {@code 'h1bapi'}. */
    public int record(String source, Instant at, String url, Integer statusCode) {
        return client.sql("""
                        insert into external_request_log (source, requested_at, url, status_code)
                        values (:source, :at, :url, :statusCode)
                        """)
                .param("source", source)
                .param("at", Timestamps.toText(at))
                .param("url", url)
                .param("statusCode", statusCode)
                .update();
    }

    /** Number of calls to {@code source} logged at or after {@code since} — the rolling-cap count. */
    public long countSince(String source, Instant since) {
        return client.sql("""
                        select count(*) from external_request_log
                        where source = :source and requested_at >= :since
                        """)
                .param("source", source)
                .param("since", Timestamps.toText(since))
                .query(Long.class)
                .single();
    }
}
