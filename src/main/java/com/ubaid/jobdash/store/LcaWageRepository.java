package com.ubaid.jobdash.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code lca_wage} table — pre-aggregated local DOL LCA disclosure wage data,
 * imported in bulk and read as a salary fallback when the external APIs have nothing.
 */
@Repository
public class LcaWageRepository {

    /**
     * A lookup key shorter than this is not eligible for word-boundary prefix matching — too
     * much noise (e.g. {@code "hp"} would prefix-match unrelated three-word entities).
     */
    private static final int MIN_PREFIX_KEY_LENGTH = 3;

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
                     sample_count, latest_data_date, employer_display)
                values
                    (:ek, :soc, :state, :p25, :p50, :p75, :max, :sampleCount, :latestDataDate, :display)
                on conflict(employer_key, soc_code, state) do update set
                    wage_p25 = excluded.wage_p25,
                    wage_p50 = excluded.wage_p50,
                    wage_p75 = excluded.wage_p75,
                    wage_max = excluded.wage_max,
                    sample_count = excluded.sample_count,
                    latest_data_date = excluded.latest_data_date,
                    employer_display = excluded.employer_display
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
                    .param("display", r.employerDisplay())
                    .update();
        }
    }

    /**
     * Looks for a wage aggregate for {@code employerKey}. LCA employer names are legal entities
     * ({@code "amazon com services"}) while the caller's key comes from a LinkedIn card
     * ({@code "amazon"}), so an exact hit is tried first and a <b>word-boundary</b> prefix hit
     * (stored key equals the lookup key, or begins with it followed by a space) second.
     *
     * <p>Preference order, exact always beating prefix at every tier:
     * <ol>
     *   <li>exact key + soc + requested state</li>
     *   <li>exact key + soc + national ({@code state = ''})</li>
     *   <li>prefix key + soc + requested state</li>
     *   <li>prefix key + soc + national</li>
     * </ol>
     * iterating {@code socCodes} in order within each tier. When several rows tie at a tier the
     * one with the highest {@code sample_count} wins (the dominant legal entity), ties broken
     * by {@code employer_key}. Prefix matching is skipped for keys shorter than
     * {@link #MIN_PREFIX_KEY_LENGTH} characters.
     */
    public Optional<LcaWageRow> lookup(String employerKey, List<String> socCodes, String state) {
        String normalizedState = state == null ? "" : state;
        boolean allowPrefix = employerKey != null && employerKey.length() >= MIN_PREFIX_KEY_LENGTH;

        for (boolean prefix : new boolean[]{false, true}) {
            if (prefix && !allowPrefix) {
                continue;
            }
            for (boolean nationalOnly : new boolean[]{false, true}) {
                if (!nationalOnly && normalizedState.isEmpty()) {
                    continue;
                }
                String tierState = nationalOnly ? "" : normalizedState;
                for (String soc : socCodes) {
                    Optional<LcaWageRow> hit = best(employerKey, soc, tierState, prefix);
                    if (hit.isPresent()) {
                        return hit;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Best row for one (key, soc, state) tier; {@code prefix} widens the key predicate to word-boundary prefixes. */
    private Optional<LcaWageRow> best(String employerKey, String socCode, String state, boolean prefix) {
        String keyPredicate = prefix
                ? "(employer_key = :ek or employer_key like :likeKey escape '\\')"
                : "employer_key = :ek";
        var spec = client.sql("select * from lca_wage where " + keyPredicate
                        + " and soc_code = :soc and state = :state")
                .param("ek", employerKey)
                .param("soc", socCode)
                .param("state", state);
        if (prefix) {
            spec = spec.param("likeKey", escapeLike(employerKey) + " %");
        }
        return spec.query(LcaWageRepository::mapRow).list().stream()
                .max(Comparator.comparingInt(LcaWageRow::sampleCount)
                        .thenComparing(Comparator.comparing(LcaWageRow::employerKey).reversed()));
    }

    /** Escapes LIKE metacharacters ({@code \ % _}) so a key is matched literally. */
    static String escapeLike(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
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
                rs.getString("latest_data_date"),
                rs.getString("employer_display")
        );
    }
}
