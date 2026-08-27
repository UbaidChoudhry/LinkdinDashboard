package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Read-only stats and destructive maintenance for the Data tab.
 *
 * <p><strong>{@link #clearJobResults()} is scoped deliberately.</strong> It removes
 * {@code job_listing} and {@code sweep_run} - the search results and run history a user
 * thinks of as "the data" - but leaves four things untouched:
 * <ul>
 *   <li>{@code exclude_word} / {@code company_blocklist} - user configuration meant to
 *       persist across sessions, not run output.</li>
 *   <li>{@code request_log} - the audit trail the rate limiter's rolling 24h budget is
 *       counted from ({@link RequestLogRepository#countSince}). Deleting it would let
 *       "clear the DB" double as a way to reset LinkedIn's request budget, which quietly
 *       defeats the safety mechanism it exists to enforce.</li>
 *   <li>{@code circuit_state} - live breaker state, not user data.</li>
 * </ul>
 */
@Repository
public class DataRepository {

    private final JdbcClient client;

    public DataRepository(JdbcClient client) {
        this.client = client;
    }

    /** Single-row aggregate over job_listing: verdict/status breakdowns plus the age range. */
    public JobStats jobStats() {
        String sql = """
                select
                    count(*) as total,
                    sum(case when filter_verdict = 'pass' then 1 else 0 end) as pass_count,
                    sum(case when filter_verdict = 'reject' then 1 else 0 end) as reject_count,
                    sum(case when filter_verdict is null then 1 else 0 end) as unevaluated_count,
                    sum(case when user_status = 'applied' then 1 else 0 end) as applied_count,
                    sum(case when user_status = 'not_interested' then 1 else 0 end) as not_interested_count,
                    sum(case when user_status is null then 1 else 0 end) as untriaged_count,
                    min(first_seen_at) as oldest_first_seen,
                    max(last_seen_at) as newest_last_seen
                from job_listing
                """;
        return client.sql(sql).query(DataRepository::mapJobStats).single();
    }

    public long countRuns() {
        return client.sql("select count(*) from sweep_run").query(Long.class).single();
    }

    public long countRequestLogEntries() {
        return client.sql("select count(*) from request_log").query(Long.class).single();
    }

    public long countExcludeWords() {
        return client.sql("select count(*) from exclude_word").query(Long.class).single();
    }

    public long countBlockedCompanies() {
        return client.sql("select count(*) from company_blocklist").query(Long.class).single();
    }

    /**
     * Deletes every job result and run record. See the class doc for exactly what this does
     * and does not touch. Callers are responsible for checking no run is currently in flight
     * before calling this.
     */
    @Transactional
    public ClearResult clearJobResults() {
        int jobsCleared = client.sql("delete from job_listing").update();
        int runsCleared = client.sql("delete from sweep_run").update();
        return new ClearResult(jobsCleared, runsCleared);
    }

    /**
     * Reclaims disk space after a clear. Must run outside any transaction (SQLite forbids
     * {@code VACUUM} inside one), so this is deliberately not {@code @Transactional} and
     * callers must invoke it as its own call, after {@link #clearJobResults()} has returned
     * and its transaction has committed.
     */
    public void vacuum() {
        client.sql("VACUUM").update();
    }

    public record JobStats(
            long total,
            long passCount,
            long rejectCount,
            long unevaluatedCount,
            long appliedCount,
            long notInterestedCount,
            long untriagedCount,
            Instant oldestFirstSeenAt,
            Instant newestLastSeenAt
    ) {
    }

    public record ClearResult(int jobsCleared, int runsCleared) {
    }

    private static JobStats mapJobStats(ResultSet rs, int rowNum) throws SQLException {
        return new JobStats(
                rs.getLong("total"),
                rs.getLong("pass_count"),
                rs.getLong("reject_count"),
                rs.getLong("unevaluated_count"),
                rs.getLong("applied_count"),
                rs.getLong("not_interested_count"),
                rs.getLong("untriaged_count"),
                Timestamps.parse(rs.getString("oldest_first_seen")),
                Timestamps.parse(rs.getString("newest_last_seen"))
        );
    }
}
