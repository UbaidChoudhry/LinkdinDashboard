package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.CompanyLinkMatch;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for {@code company_link_match}: one row per LinkedIn job the company-link finder has
 * looked at. See {@code apply.CompanyLinkFinder} and {@code V16__company_link_match.sql}.
 */
@Repository
public class CompanyLinkMatchRepository {

    private final JdbcClient client;

    public CompanyLinkMatchRepository(JdbcClient client) {
        this.client = client;
    }

    /** Inserts or replaces the finder's result for one job. */
    public void save(CompanyLinkMatch match) {
        client.sql("""
                        insert into company_link_match (job_id, url, confidence, matched_on, note, checked_at)
                        values (:jobId, :url, :confidence, :matchedOn, :note, :checkedAt)
                        on conflict(job_id) do update set
                            url = excluded.url, confidence = excluded.confidence,
                            matched_on = excluded.matched_on, note = excluded.note,
                            checked_at = excluded.checked_at
                        """)
                .param("jobId", match.jobId())
                .param("url", match.url() == null ? "" : match.url())
                .param("confidence", match.confidence())
                .param("matchedOn", String.join(",", match.matchedOn()))
                .param("note", match.note() == null ? "" : match.note())
                .param("checkedAt", Timestamps.toText(match.checkedAt()))
                .update();
    }

    /** The finder's results for a set of jobs, keyed by job id - one query, for the Results tab. */
    public Map<Long, CompanyLinkMatch> findByJobIds(Collection<Long> jobIds) {
        Map<Long, CompanyLinkMatch> byId = new HashMap<>();
        if (jobIds.isEmpty()) {
            return byId;
        }
        client.sql("select * from company_link_match where job_id in (:jobIds)")
                .param("jobIds", jobIds)
                .query(CompanyLinkMatchRepository::mapRow)
                .list()
                .forEach(m -> byId.put(m.jobId(), m));
        return byId;
    }

    static CompanyLinkMatch mapRow(ResultSet rs, int rowNum) throws SQLException {
        String matchedOn = rs.getString("matched_on");
        return new CompanyLinkMatch(
                rs.getLong("job_id"),
                rs.getString("url"),
                rs.getInt("confidence"),
                matchedOn == null || matchedOn.isBlank() ? List.of() : Arrays.asList(matchedOn.split(",")),
                rs.getString("note"),
                Timestamps.parse(rs.getString("checked_at")));
    }
}
