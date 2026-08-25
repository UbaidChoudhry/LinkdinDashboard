package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the {@code company_blocklist} table. The {@code company} column is
 * {@code collate nocase}, so all lookups/deletes are case-insensitive at the database level.
 */
@Repository
public class CompanyBlocklistRepository {

    private final JdbcClient client;

    public CompanyBlocklistRepository(JdbcClient client) {
        this.client = client;
    }

    public record BlockedCompany(String company, String reason, String evidence, Instant addedAt) {
    }

    public List<BlockedCompany> list() {
        return client.sql("select company, reason, evidence, added_at from company_blocklist order by company")
                .query((rs, rowNum) -> new BlockedCompany(
                        rs.getString("company"),
                        rs.getString("reason"),
                        rs.getString("evidence"),
                        Timestamps.parse(rs.getString("added_at"))))
                .list();
    }

    public int add(String company, String reason, String evidence, Instant addedAt) {
        return client.sql("""
                        insert into company_blocklist (company, reason, evidence, added_at)
                        values (:company, :reason, :evidence, :addedAt)
                        on conflict(company) do update set reason = excluded.reason, evidence = excluded.evidence
                        """)
                .param("company", company)
                .param("reason", reason)
                .param("evidence", evidence)
                .param("addedAt", Timestamps.toText(addedAt))
                .update();
    }

    public int delete(String company) {
        return client.sql("delete from company_blocklist where company = :company")
                .param("company", company)
                .update();
    }
}
