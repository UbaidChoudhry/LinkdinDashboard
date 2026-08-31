package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LcaWageRepositoryTest extends AbstractStoreTest {

    private static LcaWageRow row(String employer, String soc, String state, double p50) {
        return row(employer, soc, state, p50, 10);
    }

    private static LcaWageRow row(String employer, String soc, String state, double p50, int sampleCount) {
        return new LcaWageRow(employer, soc, state, p50 - 20000, p50, p50 + 20000, p50 + 50000,
                sampleCount, "2025-01-01", employer.toUpperCase() + " LLC");
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

    // ---- word-boundary prefix / alias matching ------------------------------------------------

    @Test
    void prefixMatchFindsLegalEntityAndPrefersHighestSampleCount() {
        lcaWageRepository.upsertAll(List.of(
                row("amazon com services", "15-1252", "", 165000, 1391),
                row("amazon web services", "15-1252", "", 190000, 485)));

        LcaWageRow hit = lcaWageRepository.lookup("amazon", List.of("15-1252"), "TX").orElseThrow();
        assertThat(hit.employerKey()).isEqualTo("amazon com services");
        assertThat(hit.sampleCount()).isEqualTo(1391);
    }

    @Test
    void prefixMatchIsWordBoundarySafe_metaMatchesMetaPlatformsNotMetabase() {
        lcaWageRepository.upsertAll(List.of(
                row("meta platforms", "15-1252", "", 200000, 860),
                row("metabase", "15-1252", "", 130000, 50)));

        LcaWageRow hit = lcaWageRepository.lookup("meta", List.of("15-1252"), "CA").orElseThrow();
        assertThat(hit.employerKey()).isEqualTo("meta platforms");
    }

    @Test
    void prefixMatchDoesNotCrossAWordBoundary_appDoesNotMatchApple() {
        lcaWageRepository.upsertAll(List.of(row("apple", "15-1252", "", 195000, 489)));
        assertThat(lcaWageRepository.lookup("app", List.of("15-1252"), "CA")).isEmpty();
    }

    @Test
    void exactMatchBeatsAFarLargerPrefixMatch() {
        lcaWageRepository.upsertAll(List.of(
                row("acme", "15-1252", "", 150000, 5),
                row("acme global services", "15-1252", "", 250000, 5000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252"), "CA").orElseThrow();
        assertThat(hit.employerKey()).isEqualTo("acme");
        assertThat(hit.sampleCount()).isEqualTo(5);
    }

    @Test
    void exactNationalBeatsPrefixState() {
        lcaWageRepository.upsertAll(List.of(
                row("acme", "15-1252", "", 150000, 10),
                row("acme services", "15-1252", "CA", 300000, 9000)));

        LcaWageRow hit = lcaWageRepository.lookup("acme", List.of("15-1252"), "CA").orElseThrow();
        assertThat(hit.employerKey()).isEqualTo("acme");
        assertThat(hit.state()).isEmpty();
    }

    @Test
    void shortKeyIsNotEligibleForPrefixMatching() {
        lcaWageRepository.upsertAll(List.of(row("hp enterprise", "15-1252", "", 170000, 300)));
        assertThat(lcaWageRepository.lookup("hp", List.of("15-1252"), "CA")).isEmpty();
    }
}
