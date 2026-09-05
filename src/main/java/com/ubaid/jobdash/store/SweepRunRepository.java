package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.SweepRun;
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
 * Repository for the {@code sweep_run} table.
 */
@Repository
public class SweepRunRepository {

    private final JdbcClient client;

    public SweepRunRepository(JdbcClient client) {
        this.client = client;
    }

    /**
     * Creates a new run row in status {@code running} and returns the generated id. Leaves
     * {@code sources} at its default ({@code 'linkedin'}) and {@code resume_id} null - this is
     * the overload {@code SweepService} uses for its unchanged LinkedIn-only path.
     */
    public long create(Instant startedAt, String keywords, String location, int hours, boolean testMode, Integer pageCap) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into sweep_run (started_at, status, keywords, location, hours, test_mode, page_cap)
                        values (:startedAt, 'running', :keywords, :location, :hours, :testMode, :pageCap)
                        """)
                .param("startedAt", Timestamps.toText(startedAt))
                .param("keywords", keywords)
                .param("location", location)
                .param("hours", hours)
                .param("testMode", testMode ? 1 : 0)
                .param("pageCap", pageCap)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * Creates a new run row recording its source selection and (optionally) the resume an AI
     * scan will run against - used by {@code RunOrchestrator} for any run that isn't
     * LinkedIn-only.
     */
    public long create(Instant startedAt, String keywords, String location, int hours, boolean testMode,
                        Integer pageCap, String sources, Long resumeId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into sweep_run (started_at, status, keywords, location, hours, test_mode, page_cap,
                                                sources, resume_id)
                        values (:startedAt, 'running', :keywords, :location, :hours, :testMode, :pageCap,
                                :sources, :resumeId)
                        """)
                .param("startedAt", Timestamps.toText(startedAt))
                .param("keywords", keywords)
                .param("location", location)
                .param("hours", hours)
                .param("testMode", testMode ? 1 : 0)
                .param("pageCap", pageCap)
                .param("sources", sources)
                .param("resumeId", resumeId)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Updates the progress counters on a still-running run. */
    public int updateProgress(long id, String shardsUsed, int pagesFetched, int requestsMade, int cardsSeen, int jobsNew, boolean saturated) {
        return client.sql("""
                        update sweep_run
                        set shards_used = :shardsUsed,
                            pages_fetched = :pagesFetched,
                            requests_made = :requestsMade,
                            cards_seen = :cardsSeen,
                            jobs_new = :jobsNew,
                            saturated = :saturated
                        where id = :id
                        """)
                .param("shardsUsed", shardsUsed)
                .param("pagesFetched", pagesFetched)
                .param("requestsMade", requestsMade)
                .param("cardsSeen", cardsSeen)
                .param("jobsNew", jobsNew)
                .param("saturated", saturated ? 1 : 0)
                .param("id", id)
                .update();
    }

    /** Updates the progress counters on a still-running ATS run (the {@code companies_*} equivalent of {@link #updateProgress}). */
    public int updateAtsProgress(long id, int companiesDone, int companiesTotal, int requestsMade, int cardsSeen, int jobsNew) {
        return client.sql("""
                        update sweep_run
                        set companies_done = :companiesDone,
                            companies_total = :companiesTotal,
                            requests_made = :requestsMade,
                            cards_seen = :cardsSeen,
                            jobs_new = :jobsNew
                        where id = :id
                        """)
                .param("companiesDone", companiesDone)
                .param("companiesTotal", companiesTotal)
                .param("requestsMade", requestsMade)
                .param("cardsSeen", cardsSeen)
                .param("jobsNew", jobsNew)
                .param("id", id)
                .update();
    }

    /** Marks a run finished with a terminal status (e.g. {@code completed}, {@code failed}). */
    public int finish(long id, Instant finishedAt, String status) {
        return client.sql("update sweep_run set finished_at = :finishedAt, status = :status where id = :id")
                .param("finishedAt", Timestamps.toText(finishedAt))
                .param("status", status)
                .param("id", id)
                .update();
    }

    public List<SweepRun> findRecent(int limit) {
        return client.sql("select * from sweep_run order by id desc limit :limit")
                .param("limit", limit)
                .query(SweepRunRepository::mapRow)
                .list();
    }

    public Optional<SweepRun> findById(long id) {
        return client.sql("select * from sweep_run where id = :id")
                .param("id", id)
                .query(SweepRunRepository::mapRow)
                .optional();
    }

    /**
     * Marks every unfinished run as terminated with {@code status}, and returns how many were
     * affected. Called once at startup: a run only exists on a thread inside the process that
     * started it, so any row still open when the application boots was orphaned by a crash,
     * a kill, or a restart, and can never make progress again.
     */
    public int finishAllUnfinished(Instant finishedAt, String status) {
        return client.sql("update sweep_run set status = :status, finished_at = :finishedAt where finished_at is null")
                .param("status", status)
                .param("finishedAt", Timestamps.toText(finishedAt))
                .update();
    }

    /** The most recently started run that hasn't finished yet, if any — used to refuse a second concurrent run. */
    public Optional<SweepRun> findRunning() {
        return client.sql("select * from sweep_run where finished_at is null order by id desc limit 1")
                .query(SweepRunRepository::mapRow)
                .optional();
    }

    static SweepRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        // resume_id must be read via getLong + wasNull, NOT `(Long) rs.getObject(...)`. SQLite's
        // JDBC driver hands back the narrowest type that fits, so a small resume_id arrives as an
        // Integer and the cast throws ClassCastException at runtime. It only blows up once a run
        // actually HAS a resume attached — a null column casts fine — which is why this survived
        // a green test suite and only failed against real UI traffic. wasNull() reports on the
        // most recent read, so it is checked immediately, before any other column is touched.
        long resumeIdValue = rs.getLong("resume_id");
        Long resumeId = rs.wasNull() ? null : resumeIdValue;
        return new SweepRun(
                rs.getLong("id"),
                Timestamps.parse(rs.getString("started_at")),
                Timestamps.parse(rs.getString("finished_at")),
                rs.getString("status"),
                rs.getString("keywords"),
                rs.getString("location"),
                rs.getInt("hours"),
                rs.getInt("test_mode") != 0,
                (Integer) rs.getObject("page_cap"),
                rs.getString("shards_used"),
                rs.getInt("pages_fetched"),
                rs.getInt("requests_made"),
                rs.getInt("cards_seen"),
                rs.getInt("jobs_new"),
                rs.getInt("saturated") != 0,
                rs.getString("sources"),
                resumeId,
                rs.getInt("companies_done"),
                rs.getInt("companies_total")
        );
    }
}
