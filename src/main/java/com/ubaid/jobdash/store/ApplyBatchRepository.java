package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.ApplyBatch;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code apply_batch} table: one row per call to "Apply with Claude", with
 * running counters the UI polls while a batch is in flight. See
 * {@link com.ubaid.jobdash.apply.ApplyOrchestrator} for the state machine built on top of this.
 */
@Repository
public class ApplyBatchRepository {

    private final JdbcClient client;

    public ApplyBatchRepository(JdbcClient client) {
        this.client = client;
    }

    /** Inserts a new batch row in {@code running} status and returns its generated id. */
    public long create(long resumeId, boolean submit, int total, Instant startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into apply_batch (resume_id, submit, status, total, started_at)
                        values (:resumeId, :submit, 'running', :total, :startedAt)
                        """)
                .param("resumeId", resumeId)
                .param("submit", submit ? 1 : 0)
                .param("total", total)
                .param("startedAt", Timestamps.toText(startedAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Looks up a batch by id. */
    public Optional<ApplyBatch> findById(long id) {
        return client.sql("select * from apply_batch where id = :id")
                .param("id", id)
                .query(ApplyBatchRepository::mapRow)
                .optional();
    }

    /** The batch still {@code running}, if any - at most one apply batch runs at a time. */
    public Optional<ApplyBatch> findInFlight() {
        return client.sql("select * from apply_batch where status = 'running' order by id desc limit 1")
                .query(ApplyBatchRepository::mapRow)
                .optional();
    }

    /** Updates the running counters and accumulated cost on an in-flight batch. */
    public void updateProgress(long id, int done, int submitted, int needsReview, int failed, int skipped,
                                double costUsd) {
        client.sql("""
                        update apply_batch
                        set done = :done, submitted = :submitted, needs_review = :needsReview,
                            failed = :failed, skipped = :skipped, cost_usd = :costUsd
                        where id = :id
                        """)
                .param("done", done)
                .param("submitted", submitted)
                .param("needsReview", needsReview)
                .param("failed", failed)
                .param("skipped", skipped)
                .param("costUsd", costUsd)
                .param("id", id)
                .update();
    }

    /** Marks a batch finished with its terminal status ({@code ok | cancelled | failed}). */
    public void finish(long id, String status, Instant finishedAt) {
        client.sql("update apply_batch set status = :status, finished_at = :finishedAt where id = :id")
                .param("status", status)
                .param("finishedAt", Timestamps.toText(finishedAt))
                .param("id", id)
                .update();
    }

    /**
     * Records how many new {@code profile_answer} rows this batch's run created and where its
     * end-of-run markdown report was written. Called once, after {@link #finish}.
     */
    public void setReport(long id, int newQuestions, String reportPath) {
        client.sql("update apply_batch set new_questions = :newQuestions, report_path = :reportPath where id = :id")
                .param("newQuestions", newQuestions)
                .param("reportPath", reportPath)
                .param("id", id)
                .update();
    }

    /** The most recent batches, newest first, for a history view. */
    public List<ApplyBatch> findRecent(int limit) {
        return client.sql("select * from apply_batch order by id desc limit :limit")
                .param("limit", limit)
                .query(ApplyBatchRepository::mapRow)
                .list();
    }

    static ApplyBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ApplyBatch(
                rs.getLong("id"),
                rs.getLong("resume_id"),
                rs.getInt("submit") != 0,
                rs.getString("status"),
                rs.getInt("total"),
                rs.getInt("done"),
                rs.getInt("submitted"),
                rs.getInt("needs_review"),
                rs.getInt("failed"),
                rs.getInt("skipped"),
                rs.getDouble("cost_usd"),
                Timestamps.parse(rs.getString("started_at")),
                Timestamps.parse(rs.getString("finished_at")),
                rs.getInt("new_questions"),
                rs.getString("report_path")
        );
    }
}
