package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for the {@code ai_match} table: cached Claude verdicts of a resume against a job.
 * Keyed {@code (job_id, resume_id)}, so re-scanning a job/resume pair already scanned is a
 * no-op lookup rather than a fresh CLI call - see {@link com.ubaid.jobdash.ai.ResumeMatchService}.
 */
@Repository
public class AiMatchRepository {

    private final JdbcClient client;

    public AiMatchRepository(JdbcClient client) {
        this.client = client;
    }

    /** Inserts or replaces a batch of verdicts in one transaction. */
    @Transactional
    public void upsertAll(List<AiMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        String sql = """
                insert into ai_match (job_id, resume_id, recommended, reason, model, run_id, scanned_at)
                values (:jobId, :resumeId, :recommended, :reason, :model, :runId, :scannedAt)
                on conflict(job_id, resume_id) do update set
                    recommended = excluded.recommended,
                    reason = excluded.reason,
                    model = excluded.model,
                    run_id = excluded.run_id,
                    scanned_at = excluded.scanned_at
                """;
        for (AiMatch m : matches) {
            client.sql(sql)
                    .param("jobId", m.jobId())
                    .param("resumeId", m.resumeId())
                    .param("recommended", m.recommended() ? 1 : 0)
                    .param("reason", m.reason())
                    .param("model", m.model())
                    .param("runId", m.runId())
                    .param("scannedAt", Timestamps.toText(m.scannedAt()))
                    .update();
        }
    }

    /** The cached verdicts for a set of jobs against one resume, keyed by job id. */
    public Map<Long, AiMatch> findByJobIds(Collection<Long> jobIds, long resumeId) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        List<AiMatch> rows = client.sql("""
                        select * from ai_match where job_id in (:jobIds) and resume_id = :resumeId
                        """)
                .param("jobIds", jobIds)
                .param("resumeId", resumeId)
                .query(AiMatchRepository::mapRow)
                .list();
        Map<Long, AiMatch> byJobId = new HashMap<>();
        for (AiMatch row : rows) {
            byJobId.put(row.jobId(), row);
        }
        return byJobId;
    }

    /** Which of the given job ids already have a cached verdict for this resume - used to skip re-scanning them. */
    public java.util.Set<Long> findScannedJobIds(Collection<Long> jobIds, long resumeId) {
        if (jobIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(client.sql("""
                        select job_id from ai_match where job_id in (:jobIds) and resume_id = :resumeId
                        """)
                .param("jobIds", jobIds)
                .param("resumeId", resumeId)
                .query(Long.class)
                .list());
    }

    /** Clears every cached verdict for a resume - used after the resume is replaced, to force a fresh scan. */
    public int deleteByResume(long resumeId) {
        return client.sql("delete from ai_match where resume_id = :resumeId")
                .param("resumeId", resumeId)
                .update();
    }

    /** Recommended vs. not-recommended counts among a run's cached verdicts for one resume. */
    public Counts countByRunAndResume(long runId, long resumeId) {
        return client.sql("""
                        select
                            sum(case when recommended = 1 then 1 else 0 end) as recommended,
                            sum(case when recommended = 0 then 1 else 0 end) as notRecommended
                        from ai_match
                        where run_id = :runId and resume_id = :resumeId
                        """)
                .param("runId", runId)
                .param("resumeId", resumeId)
                .query((rs, rowNum) -> new Counts(rs.getInt("recommended"), rs.getInt("notRecommended")))
                .single();
    }

    /** Recommended / not-recommended counts, as returned by {@link #countByRunAndResume}. */
    public record Counts(int recommended, int notRecommended) {
    }

    static AiMatch mapRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() reports on the MOST RECENT column read, so it has to be checked immediately
        // after the getLong it refers to. Checking it further down (after the getString calls
        // below) would be asking "was `model` null?" and would turn a null run_id into 0.
        long runIdValue = rs.getLong("run_id");
        Long runId = rs.wasNull() ? null : runIdValue;
        return new AiMatch(
                rs.getLong("job_id"),
                rs.getLong("resume_id"),
                rs.getInt("recommended") != 0,
                rs.getString("reason"),
                rs.getString("model"),
                runId,
                Timestamps.parse(rs.getString("scanned_at"))
        );
    }
}
