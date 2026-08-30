package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LcaWageRepositoryTest extends AbstractStoreTest {

    private static LcaWageRow row(String employer, String soc, String state, double p50) {
        return new LcaWageRow(employer, soc, state, p50 - 20000, p50, p50 + 20000, p50 + 50000, 10, "2025-01-01");
    }

    @Test
    void lookupPrefersStateMatchOverNational() {
        lcaWageRepository.upsertAll(List.of(
                row("acme", "15-1252", "CA", 180000),
                row("acme", "15-1252", "", 150000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252"), "CA").orElseThrow();
        assertThat(hit.state()).isEqualTo("CA");
        assertThat(hit.wageP50()).isEqualTo(180000.0);
    }

    @Test
    void lookupFallsBackToNationalWhenStateMissing() {
        lcaWageRepository.upsertAll(List.of(row("acme", "15-1252", "", 150000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252"), "NY").orElseThrow();
        assertThat(hit.state()).isEmpty();
        assertThat(hit.wageP50()).isEqualTo(150000.0);
    }

    @Test
    void lookupTriesSocCodesInOrder() {
        lcaWageRepository.upsertAll(List.of(row("acme", "15-1299", "", 140000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252", "15-1299"), "TX").orElseThrow();
        assertThat(hit.socCode()).isEqualTo("15-1299");
    }

    @Test
    void lookupReturnsEmptyWhenNothingMatches() {
        lcaWageRepository.upsertAll(List.of(row("acme", "15-1252", "", 150000)));
        assertThat(lcaWageRepository.lookup("other", List.of("15-1252"), "CA")).isEmpty();
    }

    @Test
    void upsertAllReplacesExistingAggregate() {
        lcaWageRepository.upsertAll(List.of(row("acme", "15-1252", "", 150000)));
        lcaWageRepository.upsertAll(List.of(row("acme", "15-1252", "", 165000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252"), "").orElseThrow();
        assertThat(hit.wageP50()).isEqualTo(165000.0);
    }
}
