package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Repository for the {@code salary_estimate} cache — the per-{@code (companyKey, titleKey)}
 * result of a salary lookup, kept so the external APIs are hit at most once per pair per TTL.
 */
@Repository
public class SalaryEstimateRepository {

    private final JdbcClient client;

    public SalaryEstimateRepository(JdbcClient client) {
        this.client = client;
    }

    /** Looks up a cached estimate by its composite key. */
    public Optional<SalaryEstimate> find(String companyKey, String titleKey) {
        return client.sql("select * from salary_estimate where company_key = :ck and title_key = :tk")
                .param("ck", companyKey)
                .param("tk", titleKey)
                .query(SalaryEstimateRepository::mapRow)
                .optional();
    }

    /** Inserts, or replaces every non-key column of an existing row for the same pair. */
    public int upsert(SalaryEstimate e) {
        return client.sql("""
                        insert into salary_estimate
                            (company_key, title_key, salary_min, salary_max, currency, source,
                             data_date, sample_count, fetched_at, source_detail)
                        values
                            (:ck, :tk, :min, :max, :currency, :source, :dataDate, :sampleCount, :fetchedAt, :sourceDetail)
                        on conflict(company_key, title_key) do update set
                            salary_min = excluded.salary_min,
                            salary_max = excluded.salary_max,
                            currency = excluded.currency,
                            source = excluded.source,
                            data_date = excluded.data_date,
                            sample_count = excluded.sample_count,
                            fetched_at = excluded.fetched_at,
                            source_detail = excluded.source_detail
                        """)
                .param("ck", e.companyKey())
                .param("tk", e.titleKey())
                .param("min", e.salaryMin())
                .param("max", e.salaryMax())
                .param("currency", e.currency())
                .param("source", e.source())
                .param("dataDate", e.dataDate())
                .param("sampleCount", e.sampleCount())
                .param("fetchedAt", Timestamps.toText(e.fetchedAt()))
                .param("sourceDetail", e.sourceDetail())
                .update();
    }

    /** True while {@code now} is within {@code ttl} of when the estimate was recorded. */
    public boolean isFresh(SalaryEstimate e, Instant now, Duration ttl) {
        return e.fetchedAt() != null && now.isBefore(e.fetchedAt().plus(ttl));
    }

    static SalaryEstimate mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SalaryEstimate(
                rs.getString("company_key"),
                rs.getString("title_key"),
                (Double) rs.getObject("salary_min"),
                (Double) rs.getObject("salary_max"),
                rs.getString("currency"),
                rs.getString("source"),
                rs.getString("data_date"),
                (Integer) rs.getObject("sample_count"),
                Timestamps.parse(rs.getString("fetched_at")),
                rs.getString("source_detail")
        );
    }
}
