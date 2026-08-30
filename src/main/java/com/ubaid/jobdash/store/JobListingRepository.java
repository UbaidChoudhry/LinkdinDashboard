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
     * {@code last_seen_run_id} refreshed.
     *
     * @return the number of cards that were genuinely new (did not already exist)
     */
    @Transactional
    public int upsertAll(List<JobCardInsert> cards, long runId, Instant now) {
        if (cards.isEmpty()) {
            return 0;
        }

        List<Long> ids = cards.stream().map(JobCardInsert::jobId).toList();
        Set<Long> existing = new HashSet<>(client.sql(
                        "select job_id from job_listing where job_id in (:ids)")
                .param("ids", ids)
                .query(Long.class)
                .list());

        String sql = """
                insert into job_listing
                    (job_id, source, title, company, location, posted_at,
                     first_seen_at, last_seen_at, last_seen_run_id, job_url, company_url)
                values
                    (:jobId, 'linkedin', :title, :company, :location, :postedAt,
                     :now, :now, :runId, :jobUrl, :companyUrl)
                on conflict(job_id) do update set
                    last_seen_at = excluded.last_seen_at,
                    last_seen_run_id = excluded.last_seen_run_id
                """;

        String nowText = Timestamps.toText(now);
        int newRows = 0;
        for (JobCardInsert card : cards) {
            client.sql(sql)
                    .param("jobId", card.jobId())
                    .param("title", card.title())
                    .param("company", card.company())
                    .param("location", card.location())
                    .param("postedAt", Timestamps.toText(card.postedAt()))
                    .param("now", nowText)
                    .param("runId", runId)
                    .param("jobUrl", card.jobUrl())
                    .param("companyUrl", card.companyUrl())
                    .update();
            if (!existing.contains(card.jobId())) {
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
                        order by posted_at desc
                        """)
                .param("runId", runId)
                .query(JobListingRepository::mapRow)
                .list();
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
    public int applySalary(long jobId, Double salaryMin, Double salaryMax, String source) {
        return client.sql("""
                        update job_listing
                        set salary_min = :min, salary_max = :max, salary_source = :source
                        where job_id = :id
                        """)
                .param("min", salaryMin)
                .param("max", salaryMax)
                .param("source", source)
                .param("id", jobId)
                .update();
    }

    static JobListing mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new JobListing(
                rs.getLong("job_id"),
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
                rs.getInt("suppressed") != 0
        );
    }
}
