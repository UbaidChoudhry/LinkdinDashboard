package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for the {@code ats_company} table — the catalog of Greenhouse/Lever/Workday
 * company boards, both hand-vetted (enabled) and bulk-imported (unverified). See
 * {@code V6__ats_sources.sql} for the catalog-vs-selection split.
 */
@Repository
public class AtsCompanyRepository {

    /** A company to insert during a bulk catalog import. */
    public record NewCompany(String ats, String slug, String company, String host, String site) {
    }

    /** Totals for the Sources tab summary. */
    public record CatalogCounts(Map<String, Long> byAts, Map<String, Long> byStatus) {
    }

    private final JdbcClient client;

    public AtsCompanyRepository(JdbcClient client) {
        this.client = client;
    }

    /**
     * Enabled, non-dead companies for the given ATS names, for a run to visit. Ordered by
     * {@code id} so repeated runs (with no catalog change) visit the same companies in the same
     * order.
     */
    public List<AtsCompany> findForRun(List<String> atsNames, int limit) {
        if (atsNames == null || atsNames.isEmpty()) {
            return List.of();
        }
        return client.sql("""
                        select * from ats_company
                        where enabled = 1 and status != 'dead' and ats in (:atsNames)
                        order by id
                        limit :limit
                        """)
                .param("atsNames", atsNames)
                .param("limit", limit)
                .query(AtsCompanyRepository::mapRow)
                .list();
    }

    /**
     * Paginated, filterable listing backing the Sources tab. {@code atsFilter} and
     * {@code search} are optional (null/blank matches everything); search is a case-insensitive
     * substring match against company or slug.
     */
    public List<AtsCompany> list(String atsFilter, String search, boolean enabledOnly, int limit, int offset) {
        String like = likePattern(search);
        return client.sql("""
                        select * from ats_company
                        where (:ats is null or ats = :ats)
                          and (:enabledOnly = 0 or enabled = 1)
                          and (:like is null or lower(company) like :like or lower(slug) like :like)
                        order by company, slug
                        limit :limit offset :offset
                        """)
                .param("ats", blankToNull(atsFilter))
                .param("enabledOnly", enabledOnly ? 1 : 0)
                .param("like", like)
                .param("limit", limit)
                .param("offset", offset)
                .query(AtsCompanyRepository::mapRow)
                .list();
    }

    /** Total row count matching the same filters as {@link #list}, for pagination. */
    public long count(String atsFilter, String search, boolean enabledOnly) {
        String like = likePattern(search);
        return client.sql("""
                        select count(*) from ats_company
                        where (:ats is null or ats = :ats)
                          and (:enabledOnly = 0 or enabled = 1)
                          and (:like is null or lower(company) like :like or lower(slug) like :like)
                        """)
                .param("ats", blankToNull(atsFilter))
                .param("enabledOnly", enabledOnly ? 1 : 0)
                .param("like", like)
                .query(Long.class)
                .single();
    }

    public Optional<AtsCompany> findById(long id) {
        return client.sql("select * from ats_company where id = :id")
                .param("id", id)
                .query(AtsCompanyRepository::mapRow)
                .optional();
    }

    public int setEnabled(long id, boolean enabled) {
        return client.sql("update ats_company set enabled = :enabled where id = :id")
                .param("enabled", enabled ? 1 : 0)
                .param("id", id)
                .update();
    }

    /** Caches a resolved Workday site id against an existing row. */
    public int upsertSite(long id, String site) {
        return client.sql("update ats_company set site = :site where id = :id")
                .param("site", site)
                .param("id", id)
                .update();
    }

    /**
     * Inserts a company row directly, e.g. a manual add from the Sources tab. Returns the new
     * row's id, or empty if {@code (ats, slug)} already exists.
     */
    public Optional<Long> insertOne(String ats, String slug, String company, String host, String site,
                                     boolean enabled, String status, Instant addedAt) {
        int updated = client.sql("""
                        insert or ignore into ats_company
                            (ats, slug, company, host, site, enabled, status, added_at)
                        values
                            (:ats, :slug, :company, :host, :site, :enabled, :status, :addedAt)
                        """)
                .param("ats", ats)
                .param("slug", slug)
                .param("company", company)
                .param("host", host)
                .param("site", site)
                .param("enabled", enabled ? 1 : 0)
                .param("status", status)
                .param("addedAt", Timestamps.toText(addedAt))
                .update();
        if (updated == 0) {
            return Optional.empty();
        }
        return client.sql("select id from ats_company where ats = :ats and slug = :slug")
                .param("ats", ats)
                .param("slug", slug)
                .query(Long.class)
                .optional();
    }

    /**
     * Bulk-inserts catalog rows for the importer. Uses {@code insert or ignore} against the
     * {@code (ats, slug)} unique constraint, so a company the user already configured — enabled
     * or not — is left completely untouched. Returns the number of rows actually inserted.
     */
    public int insertOrIgnore(List<NewCompany> companies, boolean enabled, String status, Instant addedAt) {
        int inserted = 0;
        String addedAtText = Timestamps.toText(addedAt);
        for (NewCompany c : companies) {
            int updated = client.sql("""
                            insert or ignore into ats_company
                                (ats, slug, company, host, site, enabled, status, added_at)
                            values
                                (:ats, :slug, :company, :host, :site, :enabled, :status, :addedAt)
                            """)
                    .param("ats", c.ats())
                    .param("slug", c.slug())
                    .param("company", c.company())
                    .param("host", c.host())
                    .param("site", c.site())
                    .param("enabled", enabled ? 1 : 0)
                    .param("status", status)
                    .param("addedAt", addedAtText)
                    .update();
            inserted += updated;
        }
        return inserted;
    }

    public int delete(long id) {
        return client.sql("delete from ats_company where id = :id")
                .param("id", id)
                .update();
    }

    /** Deletes every row already marked dead — used by the importer's {@code --prune}. */
    public int deleteDead() {
        return client.sql("delete from ats_company where status = 'dead'").update();
    }

    /** Marks a successful check: clears the failure streak, marks active, stamps timestamps. */
    public int recordSuccess(long id, Instant at, int jobCount) {
        return client.sql("""
                        update ats_company set
                            status = 'active',
                            consecutive_failures = 0,
                            last_ok_at = :at,
                            last_checked_at = :at,
                            last_job_count = :jobCount
                        where id = :id
                        """)
                .param("at", Timestamps.toText(at))
                .param("jobCount", jobCount)
                .param("id", id)
                .update();
    }

    /**
     * Records a failed check: increments the failure streak and stamps {@code last_checked_at}.
     * Once the streak reaches {@code deadThreshold}, marks the row {@code status='dead'} and
     * {@code enabled=0} in the same statement — SQLite evaluates every right-hand side against
     * the row's pre-update values, so {@code consecutive_failures + 1} below is consistent with
     * the value actually stored.
     */
    public int recordFailure(long id, Instant at, int deadThreshold) {
        return client.sql("""
                        update ats_company set
                            consecutive_failures = consecutive_failures + 1,
                            last_checked_at = :at,
                            status = case when consecutive_failures + 1 >= :threshold then 'dead' else status end,
                            enabled = case when consecutive_failures + 1 >= :threshold then 0 else enabled end
                        where id = :id
                        """)
                .param("at", Timestamps.toText(at))
                .param("threshold", deadThreshold)
                .param("id", id)
                .update();
    }

    /** Totals by {@code ats} and by {@code status}, for the Sources tab summary. */
    public CatalogCounts counts() {
        Map<String, Long> byAts = new LinkedHashMap<>();
        List<GroupCount> atsCounts = client.sql("select ats as k, count(*) as n from ats_company group by ats order by ats")
                .query(AtsCompanyRepository::mapGroupCount)
                .list();
        for (GroupCount gc : atsCounts) {
            byAts.put(gc.key(), gc.count());
        }

        Map<String, Long> byStatus = new LinkedHashMap<>();
        List<GroupCount> statusCounts = client.sql("select status as k, count(*) as n from ats_company group by status order by status")
                .query(AtsCompanyRepository::mapGroupCount)
                .list();
        for (GroupCount gc : statusCounts) {
            byStatus.put(gc.key(), gc.count());
        }

        return new CatalogCounts(byAts, byStatus);
    }

    private record GroupCount(String key, long count) {
    }

    private static GroupCount mapGroupCount(ResultSet rs, int rowNum) throws SQLException {
        return new GroupCount(rs.getString("k"), rs.getLong("n"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Lowercased {@code %term%} for a case-insensitive substring search, or null for "no filter". */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase() + "%";
    }

    static AtsCompany mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AtsCompany(
                rs.getLong("id"),
                rs.getString("ats"),
                rs.getString("slug"),
                rs.getString("company"),
                rs.getString("host"),
                rs.getString("site"),
                rs.getInt("enabled") != 0,
                rs.getString("status"),
                rs.getInt("consecutive_failures"),
                Timestamps.parse(rs.getString("last_checked_at")),
                Timestamps.parse(rs.getString("last_ok_at")),
                (Integer) rs.getObject("last_job_count"),
                Timestamps.parse(rs.getString("added_at"))
        );
    }
}
