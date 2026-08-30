package com.ubaid.jobdash.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code lca_wage} table — pre-aggregated local DOL LCA disclosure wage data,
 * imported in bulk and read as a salary fallback when the external APIs have nothing.
 */
@Repository
public class LcaWageRepository {

    private final JdbcClient client;

    public LcaWageRepository(JdbcClient client) {
        this.client = client;
    }

    /** Replaces the aggregate for each {@code (employerKey, socCode, state)} in one transaction. */
    @Transactional
    public void upsertAll(Collection<LcaWageRow> rows) {
        String sql = """
                insert into lca_wage
                    (employer_key, soc_code, state, wage_p25, wage_p50, wage_p75, wage_max,
                     sample_count, latest_data_date)
                values
                    (:ek, :soc, :state, :p25, :p50, :p75, :max, :sampleCount, :latestDataDate)
                on conflict(employer_key, soc_code, state) do update set
                    wage_p25 = excluded.wage_p25,
                    wage_p50 = excluded.wage_p50,
                    wage_p75 = excluded.wage_p75,
                    wage_max = excluded.wage_max,
                    sample_count = excluded.sample_count,
                    latest_data_date = excluded.latest_data_date
                """;
        for (LcaWageRow r : rows) {
            client.sql(sql)
                    .param("ek", r.employerKey())
                    .param("soc", r.socCode())
                    .param("state", r.state() == null ? "" : r.state())
                    .param("p25", r.wageP25())
                    .param("p50", r.wageP50())
                    .param("p75", r.wageP75())
                    .param("max", r.wageMax())
                    .param("sampleCount", r.sampleCount())
                    .param("latestDataDate", r.latestDataDate())
                    .update();
        }
    }

    /**
     * Looks for a wage aggregate for {@code employerKey}: each SOC code in {@code socCodes} is
     * tried first against the requested {@code state}, then against the {@code ""} national
     * aggregate. The first hit wins.
     */
    public Optional<LcaWageRow> lookup(String employerKey, List<String> socCodes, String state) {
        String normalizedState = state == null ? "" : state;
        for (String soc : socCodes) {
            if (!normalizedState.isEmpty()) {
                Optional<LcaWageRow> hit = one(employerKey, soc, normalizedState);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            Optional<LcaWageRow> national = one(employerKey, soc, "");
            if (national.isPresent()) {
                return national;
            }
        }
        return Optional.empty();
    }

    private Optional<LcaWageRow> one(String employerKey, String socCode, String state) {
        return client.sql("""
                        select * from lca_wage
                        where employer_key = :ek and soc_code = :soc and state = :state
                        """)
                .param("ek", employerKey)
                .param("soc", socCode)
                .param("state", state)
                .query(LcaWageRepository::mapRow)
                .optional();
    }

    static LcaWageRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LcaWageRow(
                rs.getString("employer_key"),
                rs.getString("soc_code"),
                rs.getString("state"),
                (Double) rs.getObject("wage_p25"),
                (Double) rs.getObject("wage_p50"),
                (Double) rs.getObject("wage_p75"),
                (Double) rs.getObject("wage_max"),
                rs.getInt("sample_count"),
                rs.getString("latest_data_date")
        );
    }
}
