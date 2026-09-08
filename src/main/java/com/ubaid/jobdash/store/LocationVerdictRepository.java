package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import com.ubaid.jobdash.source.location.LocationVerdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for {@code location_verdict} — the cache of Claude's US/not-US decisions, keyed on a
 * normalised location string.
 *
 * <p>This cache is the whole cost argument for the feature: distinct location strings repeat
 * heavily across postings, so caching them turns "one CLI call per job" into roughly one call per
 * run. There is deliberately no TTL — which country a location string names does not change.
 */
@Repository
public class LocationVerdictRepository {

    private final JdbcClient client;

    public LocationVerdictRepository(JdbcClient client) {
        this.client = client;
    }

    /** The cached verdicts for a set of normalised keys, keyed by {@code location_key}. */
    public Map<String, LocationVerdict> findByKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<LocationVerdict> rows = client.sql("select * from location_verdict where location_key in (:keys)")
                .param("keys", keys)
                .query(LocationVerdictRepository::mapRow)
                .list();
        Map<String, LocationVerdict> byKey = new HashMap<>();
        for (LocationVerdict row : rows) {
            byKey.put(row.locationKey(), row);
        }
        return byKey;
    }

    /** Writes a batch of decisions, replacing any existing entry for the same key. */
    @Transactional
    public void upsertAll(List<LocationVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return;
        }
        String sql = """
                insert into location_verdict
                    (location_key, location_sample, in_us, confident, model, decided_at)
                values
                    (:key, :sample, :inUs, :confident, :model, :decidedAt)
                on conflict(location_key) do update set
                    location_sample = excluded.location_sample,
                    in_us = excluded.in_us,
                    confident = excluded.confident,
                    model = excluded.model,
                    decided_at = excluded.decided_at
                """;
        for (LocationVerdict v : verdicts) {
            client.sql(sql)
                    .param("key", v.locationKey())
                    .param("sample", v.locationSample())
                    .param("inUs", v.inUs() ? 1 : 0)
                    .param("confident", v.confident() ? 1 : 0)
                    .param("model", v.model())
                    .param("decidedAt", Timestamps.toText(v.decidedAt()))
                    .update();
        }
    }

    /** Total cached decisions, for reporting how much of a run was served from cache. */
    public int count() {
        return client.sql("select count(*) from location_verdict").query(Integer.class).single();
    }

    static LocationVerdict mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LocationVerdict(
                rs.getString("location_key"),
                rs.getString("location_sample"),
                rs.getInt("in_us") != 0,
                rs.getInt("confident") != 0,
                rs.getString("model"),
                Timestamps.parse(rs.getString("decided_at")));
    }
}
