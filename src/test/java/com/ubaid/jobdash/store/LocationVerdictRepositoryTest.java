package com.ubaid.jobdash.store;

import com.ubaid.jobdash.source.location.LocationVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@code location_verdict} cache — the reason this feature costs ~one call per run. */
class LocationVerdictRepositoryTest extends AbstractStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private LocationVerdictRepository repository;

    @BeforeEach
    void setUpAdditional() {
        repository = new LocationVerdictRepository(client);
    }

    private LocationVerdict verdict(String key, boolean inUs, boolean confident) {
        return new LocationVerdict(key, key, inUs, confident, "sonnet", NOW);
    }

    @Test
    void storesAndReadsBackVerdictsByKey() {
        repository.upsertAll(List.of(
                verdict("us - austin, tx", true, true),
                verdict("israel, yokneam", false, true),
                verdict("11 locations", true, false)));

        Map<String, LocationVerdict> found =
                repository.findByKeys(Set.of("us - austin, tx", "israel, yokneam", "11 locations"));

        assertThat(found).hasSize(3);
        assertThat(found.get("us - austin, tx").inUs()).isTrue();
        assertThat(found.get("israel, yokneam").inUs()).isFalse();
        assertThat(found.get("11 locations").confident())
                .as("low confidence must survive the round trip - the UI flags on it")
                .isFalse();
        assertThat(found.get("us - austin, tx").decidedAt()).isEqualTo(NOW);
    }

    @Test
    void findByKeysReturnsOnlyWhatIsCachedSoTheRestCanBeClassified() {
        repository.upsertAll(List.of(verdict("cached", true, true)));

        Map<String, LocationVerdict> found = repository.findByKeys(Set.of("cached", "not cached"));

        assertThat(found).containsOnlyKeys("cached");
    }

    @Test
    void anEmptyKeySetIsANoOpRatherThanAMalformedInClause() {
        assertThat(repository.findByKeys(Set.of())).isEmpty();
        repository.upsertAll(List.of());
        assertThat(repository.count()).isZero();
    }

    @Test
    void reClassifyingAKeyReplacesTheEarlierVerdict() {
        repository.upsertAll(List.of(verdict("remote - ca", true, false)));
        repository.upsertAll(List.of(verdict("remote - ca", false, true)));

        LocationVerdict stored = repository.findByKeys(Set.of("remote - ca")).get("remote - ca");

        assertThat(stored.inUs()).isFalse();
        assertThat(stored.confident()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }
}
