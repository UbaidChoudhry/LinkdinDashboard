package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Timestamps;
import com.ubaid.jobdash.domain.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Repository for the {@code job_listing} table.
 */
@Repository
public class JobListingRepository {

    private final JdbcClient client;

    public JobListingRepository(JdbcClient client) {
        this.client = client;
    }

    /**
     * Upserts a batch of parsed job cards seen during sweep run {@code runId}.
     *
     * <p>{@code first_seen_at} is only ever written on initial insert; a conflicting row keeps
     * its original {@code first_seen_at} and only has {@code last_seen_at} /
     * {@code last_seen_run_id} refreshed. The conflict target is the natural key
     * {@code (source, source_job_id)}, not the surrogate {@code job_id} - that column is left
     * for SQLite's autoincrement to assign on genuine insert. A conflicting row's
     * {@code description} is only overwritten when the incoming one is non-null, so an ATS
     * re-fetch can refresh a description but a source with no inline description (LinkedIn)
     * never blanks one out.
     *
     * @return the number of cards that were genuinely new (did not already exist)
     */
    @Transactional
    public int upsertAll(List<JobCardInsert> cards, long runId, Instant now) {
        if (cards.isEmpty()) {
            return 0;
        }

        List<String> sources = cards.stream().map(JobCardInsert::source).distinct().toList();
        List<String> sourceJobIds = cards.stream().map(JobCardInsert::sourceJobId).distinct().toList();
        // char(1) is a safe composite-key delimiter: source and source_job_id are short
        // identifier-like strings that never contain a control character.
        Set<String> existing = new HashSet<>(client.sql(
                        "select source || char(1) || source_job_id from job_listing " +
                                "where source in (:sources) and source_job_id in (:sourceJobIds)")
                .param("sources", sources)
                .param("sourceJobIds", sourceJobIds)
                .query(String.class)
                .list());

        String sql = """
                insert into job_listing
                    (source, source_job_id, title, company, location, posted_at,
                     first_seen_at, last_seen_at, last_seen_run_id, job_url, company_url, description)
                values
                    (:source, :sourceJobId, :title, :company, :location, :postedAt,
                     :now, :now, :runId, :jobUrl, :companyUrl, :description)
                on conflict(source, source_job_id) do update set
                    last_seen_at = excluded.last_seen_at,
                    last_seen_run_id = excluded.last_seen_run_id,
                    description = coalesce(excluded.description, job_listing.description)
                """;

        String nowText = Timestamps.toText(now);
        int newRows = 0;
        for (JobCardInsert card : cards) {
            client.sql(sql)
                    .param("source", card.source())
                    .param("sourceJobId", card.sourceJobId())
                    .param("title", card.title())
                    .param("company", card.company())
                    .param("location", card.location())
                    .param("postedAt", Timestamps.toText(card.postedAt()))
                    .param("now", nowText)
                    .param("runId", runId)
                    .param("jobUrl", card.jobUrl())
                    .param("companyUrl", card.companyUrl())
                    .param("description", card.description())
                    .update();
            if (!existing.contains(card.source() + "\u0001" + card.sourceJobId())) {
                newRows++;
            }
        }
        return newRows;
    }

    /** Rows for the Search tab: everything seen in a given run, filtered by verdict and (optional) user status. */
    public List<JobListing> findByRunAndVerdictAndUserStatus(long runId, FilterVerdict verdict, UserStatus userStatus) {
        var spec = client.sql("""
                        select * from job_listing
                        where last_seen_run_id = :runId
                          and (:hasVerdict = 0 or filter_verdict = :verdict)
                          and (:hasUserStatus = 0 or user_status = :userStatus)
                        order by posted_at desc
                        """)
                .param("runId", runId)
                .param("hasVerdict", verdict == null ? 0 : 1)
                .param("verdict", verdict == null ? null : verdict.toDb())
                .param("hasUserStatus", userStatus == null ? 0 : 1)
                .param("userStatus", userStatus == null ? null : userStatus.toDb());
        return spec.query(JobListingRepository::mapRow).list();
    }

    /** Rows for the Applied / Not-interested tabs: all runs, filtered by user status only. */
    public List<JobListing> findByUserStatus(UserStatus userStatus) {
        return client.sql("select * from job_listing where user_status = :userStatus order by user_status_at desc")
                .param("userStatus", userStatus.toDb())
                .query(JobListingRepository::mapRow)
                .list();
    }

    /**
     * Rows for the Search tab (current run only): everything seen in a given run with the given
     * verdict that has never had a user status applied. Unlike
     * {@link #findByRunAndVerdictAndUserStatus}, {@code null} here means "user_status is null",
     * not "don't filter on user status".
     */
    public List<JobListing> findByRunAndVerdictAndNullUserStatus(long runId, FilterVerdict verdict) {
        return client.sql("""
                        select * from job_listing
                        where last_seen_run_id = :runId and filter_verdict = :verdict and user_status is null
                        -- Hide only a CONFIDENTLY non-US row. The coalesce is load-bearing:
                        -- SQLite comparisons against NULL yield NULL, so without it an
                        -- unclassified row makes the whole NOT(...) NULL and WHERE drops it -
                        -- silently hiding the very rows meant to stay visible. See V9.
                        and not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)
                        order by posted_at desc
                        """)
                .param("runId", runId)
                .param("verdict", verdict.toDb())
                .query(JobListingRepository::mapRow)
                .list();
    }

    /**
     * Rows for the Search tab with {@code includePreviousRuns=true}: the run filter is dropped
     * entirely, but verdict and "never triaged" still apply.
     */
    public List<JobListing> findByVerdictAndNullUserStatus(FilterVerdict verdict) {
        return client.sql("""
                        select * from job_listing
                        where filter_verdict = :verdict and user_status is null
                        -- Hide only a CONFIDENTLY non-US row. The coalesce is load-bearing:
                        -- SQLite comparisons against NULL yield NULL, so without it an
                        -- unclassified row makes the whole NOT(...) NULL and WHERE drops it -
                        -- silently hiding the very rows meant to stay visible. See V9.
                        and not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)
                        order by posted_at desc
                        """)
                .param("verdict", verdict.toDb())
                .query(JobListingRepository::mapRow)
                .list();
    }

    /** Single row lookup by primary key, for existence checks and read-back after a mutation. */
    public java.util.Optional<JobListing> findById(long jobId) {
        return client.sql("select * from job_listing where job_id = :jobId")
                .param("jobId", jobId)
                .query(JobListingRepository::mapRow)
                .optional();
    }

    /** One row of the company-volume relay-detection report; see {@link #companyVolumeSince}. */
    public record CompanyVolume(String company, int postings, int titles, int locations) {
    }

    /**
     * Structural relay-detection signal from card data alone: companies that have posted more
     * than {@code threshold} distinct listings since {@code since}, with a count of how many
     * distinct titles and locations those postings span. Read-only — never writes to
     * {@code company_blocklist}; a human decides what to do with this.
     */
    public List<CompanyVolume> companyVolumeSince(Instant since, int threshold) {
        return client.sql("""
                        select company, count(*) as postings, count(distinct title) as titles,
                               count(distinct location) as locations
                        from job_listing
                        where first_seen_at > :since
                        group by company having count(*) > :threshold
                        order by postings desc
                        """)
                .param("since", Timestamps.toText(since))
                .param("threshold", threshold)
                .query((rs, rowNum) -> new CompanyVolume(
                        rs.getString("company"), rs.getInt("postings"), rs.getInt("titles"), rs.getInt("locations")))
                .list();
    }

    /** Sets (or clears, when {@code status} is null) the user_status / user_status_at pair on one job. */
    public int setUserStatus(long jobId, UserStatus status, Instant at) {
        return client.sql("update job_listing set user_status = :status, user_status_at = :at where job_id = :jobId")
                .param("status", status == null ? null : status.toDb())
                .param("at", Timestamps.toText(at))
                .param("jobId", jobId)
                .update();
    }

    /**
     * Rows whose filter_version is older than {@code currentVersion}, for re-evaluation.
     * Includes rows with a null filter_version (never evaluated), since SQL's {@code <}
     * comparison against null is never true.
     */
    public List<JobListing> findWithFilterVersionLessThan(int currentVersion) {
        return client.sql("select * from job_listing where filter_version < :currentVersion or filter_version is null")
                .param("currentVersion", currentVersion)
                .query(JobListingRepository::mapRow)
                .list();
    }

    /**
     * Rows for salary enrichment: everything seen in {@code runId} that passed the filter and
     * has no salary yet ({@code salary_source is null}). The verdict predicate is the literal
     * lowercase {@code 'pass'} the partial index uses (see HANDOFF.md §3).
     */
    public List<JobListing> findPassingWithoutSalaryByRun(long runId) {
        return client.sql("""
                        select * from job_listing
                        where last_seen_run_id = :runId and filter_verdict = 'pass' and salary_source is null
                        -- Hide only a CONFIDENTLY non-US row. The coalesce is load-bearing:
                        -- SQLite comparisons against NULL yield NULL, so without it an
                        -- unclassified row makes the whole NOT(...) NULL and WHERE drops it -
                        -- silently hiding the very rows meant to stay visible. See V9.
                        and not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)
                        order by posted_at desc
                        """)
                .param("runId", runId)
                .query(JobListingRepository::mapRow)
                .list();
    }

    /**
     * Rows eligible for AI resume matching from one run: passing the filter, not yet triaged by
     * the user, and carrying a non-blank description. Sources with no inline description
     * (LinkedIn) are excluded here rather than sent to the CLI to be scored on nothing.
     */
    public List<JobListing> findScannableByRun(long runId) {
        return client.sql("""
                        select * from job_listing
                        where last_seen_run_id = :runId and filter_verdict = 'pass' and user_status is null
                          and description is not null and trim(description) != ''
                          -- Hide only a CONFIDENTLY non-US row; unclassified and low-confidence
                          -- rows stay scannable. coalesce is load-bearing - see the note above.
                          and not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)
                        order by posted_at desc
                        """)
                .param("runId", runId)
                .query(JobListingRepository::mapRow)
                .list();
    }

    /** The same eligibility shape as {@link #findScannableByRun}, for a manual re-scan of a specific job set. */
    public List<JobListing> findScannableByIds(java.util.Collection<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return client.sql("""
                        select * from job_listing
                        where job_id in (:jobIds) and filter_verdict = 'pass' and user_status is null
                          and description is not null and trim(description) != ''
                          -- Hide only a CONFIDENTLY non-US row; unclassified and low-confidence
                          -- rows stay scannable. coalesce is load-bearing - see the note above.
                          and not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)
                        order by posted_at desc
                        """)
                .param("jobIds", jobIds)
                .query(JobListingRepository::mapRow)
                .list();
    }

    /**
     * The distinct, non-blank location strings seen in one run — the input to location
     * classification. Distinct because these strings repeat heavily across postings, and each
     * distinct one costs a slot in a Claude batch.
     */
    public List<String> findDistinctLocationsByRun(long runId) {
        return client.sql("""
                        select distinct location from job_listing
                        where last_seen_run_id = :runId and location is not null and trim(location) <> ''
                        order by location
                        """)
                .param("runId", runId)
                .query(String.class)
                .list();
    }

    /**
     * Stamps one location verdict onto every row of {@code runId} carrying that exact raw location
     * string.
     *
     * <p>Scoped to the run deliberately: the same string could appear on rows collected by an
     * earlier run under different settings, and silently re-judging those is not this run's job.
     *
     * @return the number of rows updated
     */
    public int applyLocationVerdict(long runId, String rawLocation, boolean inUs, boolean confident) {
        return client.sql("""
                        update job_listing
                        set location_us = :inUs, location_confident = :confident
                        where last_seen_run_id = :runId and location = :location
                        """)
                .param("inUs", inUs ? 1 : 0)
                .param("confident", confident ? 1 : 0)
                .param("runId", runId)
                .param("location", rawLocation)
                .update();
    }

    /** Rows that have never been evaluated by the filter engine yet (freshly inserted). */
    public List<JobListing> findWithoutVerdict() {
        return client.sql("select * from job_listing where filter_verdict is null")
                .query(JobListingRepository::mapRow)
                .list();
    }

    /** One row's worth of a filter verdict write, keyed by job id. */
    public record VerdictUpdate(long jobId, FilterVerdict verdict, String reason) {
    }

    /**
     * Writes {@code filter_verdict}, {@code reject_reason} and {@code filter_version} for a
     * batch of jobs in one transaction. Never deletes rows — a rejected row stays in the table
     * so a rule change can flip it back to {@code pass} without re-scraping.
     *
     * @return the number of rows updated
     */
    @Transactional
    public int applyVerdicts(List<VerdictUpdate> updates, int filterVersion) {
        if (updates.isEmpty()) {
            return 0;
        }
        String sql = """
                update job_listing
                set filter_verdict = :verdict, reject_reason = :reason, filter_version = :filterVersion
                where job_id = :jobId
                """;
        int count = 0;
        for (VerdictUpdate update : updates) {
            count += client.sql(sql)
                    .param("verdict", update.verdict().toDb())
                    .param("reason", update.reason())
                    .param("filterVersion", filterVersion)
                    .param("jobId", update.jobId())
                    .update();
        }
        return count;
    }

    /**
     * Writes the enriched salary band and its provenance onto one job.
     *
     * @return 1 if the job existed and was updated, 0 otherwise
     */
    public int applySalary(long jobId, Double salaryMin, Double salaryMax, String source, String sourceDetail) {
        return client.sql("""
                        update job_listing
                        set salary_min = :min, salary_max = :max,
                            salary_source = :source, salary_source_detail = :sourceDetail
                        where job_id = :id
                        """)
                .param("min", salaryMin)
                .param("max", salaryMax)
                .param("source", source)
                .param("sourceDetail", sourceDetail)
                .param("id", jobId)
                .update();
    }

    /** Reads a 0/1/NULL column as a tri-state, preserving NULL rather than flattening it to false. */
    private static Boolean nullableBool(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value != 0;
    }

    static JobListing mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new JobListing(
                rs.getLong("job_id"),
                rs.getString("source_job_id"),
                rs.getString("source"),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("location"),
                Timestamps.parse(rs.getString("posted_at")),
                Timestamps.parse(rs.getString("first_seen_at")),
                Timestamps.parse(rs.getString("last_seen_at")),
                rs.getLong("last_seen_run_id"),
                rs.getString("job_url"),
                rs.getString("company_url"),
                FilterVerdict.fromDb(rs.getString("filter_verdict")),
                (Integer) rs.getObject("filter_version"),
                rs.getString("reject_reason"),
                UserStatus.fromDb(rs.getString("user_status")),
                Timestamps.parse(rs.getString("user_status_at")),
                Timestamps.parse(rs.getString("detail_fetched_at")),
                rs.getString("detail_status"),
                rs.getString("apply_url"),
                rs.getString("apply_domain"),
                rs.getString("description"),
                rs.getString("description_hash"),
                (Double) rs.getObject("salary_min"),
                (Double) rs.getObject("salary_max"),
                rs.getString("salary_source"),
                rs.getString("salary_source_detail"),
                rs.getInt("suppressed") != 0,
                // Tri-state: NULL means "not classified yet", which is NOT the same as "not US".
                // getBoolean() would flatten NULL to false and hide every unclassified row.
                nullableBool(rs, "location_us"),
                nullableBool(rs, "location_confident")
        );
    }
}
