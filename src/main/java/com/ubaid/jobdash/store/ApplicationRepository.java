package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for the {@code job_application} table: one row per job attempted within an
 * {@link com.ubaid.jobdash.domain.ApplyBatch}. {@link #findLatestByJobIds} mirrors
 * {@code AiMatchRepository#findByJobIds}'s batched-lookup shape, but resolves to the single
 * newest row per job id (a job can be attempted across more than one batch), using the
 * {@code job_application_job (job_id, id desc)} index.
 */
@Repository
public class ApplicationRepository {

    private final JdbcClient client;

    public ApplicationRepository(JdbcClient client) {
        this.client = client;
    }

    /** Inserts a new job_application row (typically {@code queued}) and returns its generated id. */
    public long create(long batchId, long jobId, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into job_application (batch_id, job_id, status)
                        values (:batchId, :jobId, :status)
                        """)
                .param("batchId", batchId)
                .param("jobId", jobId)
                .param("status", status)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Updates one job's outcome: status, notes, accumulated cost, and the started/finished timestamps. */
    public void update(long id, String status, String notes, double costUsd, Instant startedAt, Instant finishedAt) {
        client.sql("""
                        update job_application
                        set status = :status, notes = :notes, cost_usd = :costUsd,
                            started_at = :startedAt, finished_at = :finishedAt
                        where id = :id
                        """)
                .param("status", status)
                .param("notes", notes)
                .param("costUsd", costUsd)
                .param("startedAt", Timestamps.toText(startedAt))
                .param("finishedAt", Timestamps.toText(finishedAt))
                .param("id", id)
                .update();
    }

    /** Every job row belonging to one batch, in insertion (id) order. */
    public List<JobApplication> findByBatch(long batchId) {
        return client.sql("select * from job_application where batch_id = :batchId order by id")
                .param("batchId", batchId)
                .query(ApplicationRepository::mapRow)
                .list();
    }

    /**
     * The single newest job_application row for each of the given job ids, across all batches -
     * used to show the current apply status on the Results/Jobs tab, keyed by job id.
     */
    public Map<Long, JobApplication> findLatestByJobIds(Collection<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        List<JobApplication> rows = client.sql("""
                        select ja.* from job_application ja
                        join (
                            select job_id, max(id) as max_id
                            from job_application
                            where job_id in (:jobIds)
                            group by job_id
                        ) latest on ja.job_id = latest.job_id and ja.id = latest.max_id
                        """)
                .param("jobIds", jobIds)
                .query(ApplicationRepository::mapRow)
                .list();
        Map<Long, JobApplication> byJobId = new HashMap<>();
        for (JobApplication row : rows) {
            byJobId.put(row.jobId(), row);
        }
        return byJobId;
    }

    static JobApplication mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new JobApplication(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("job_id"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getDouble("cost_usd"),
                Timestamps.parse(rs.getString("started_at")),
                Timestamps.parse(rs.getString("finished_at"))
        );
    }
}
