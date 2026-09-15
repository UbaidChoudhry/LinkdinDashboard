package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.store.AtsCompanyRepository.NewCompany;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real temp SQLite + genuine Flyway migrations (see {@link AbstractStoreTest}), so the V6 seed
 * data and every hand-written query here are exercised against the actual schema.
 */
class AtsCompanyRepositoryTest extends AbstractStoreTest {

    private AtsCompanyRepository repository;

    @BeforeEach
    void setUpRepository() {
        this.repository = new AtsCompanyRepository(client);
    }

    @Test
    void seedContainsExactlyFortyTwoEnabledActiveCompanies() {
        long total = repository.count(null, null, false);
        assertThat(total).isEqualTo(42);

        long enabledCount = repository.count(null, null, true);
        assertThat(enabledCount).isEqualTo(42);

        List<AtsCompany> all = repository.list(null, null, false, 100, 0);
        assertThat(all).hasSize(42);
        assertThat(all).allMatch(AtsCompany::enabled);
        assertThat(all).allMatch(c -> "active".equals(c.status()));
    }

    @Test
    void seedIncludesKnownCompaniesAcrossAllThreeAts() {
        AtsCompanyRepository.CatalogCounts counts = repository.counts();
        assertThat(counts.byAts()).containsEntry("greenhouse", 26L);
        assertThat(counts.byAts()).containsEntry("lever", 10L);
        assertThat(counts.byAts()).containsEntry("workday", 6L);
        assertThat(counts.byStatus()).containsEntry("active", 42L);

        List<AtsCompany> nvidia = repository.list("workday", "nvidia", false, 10, 0);
        assertThat(nvidia).hasSize(1);
        assertThat(nvidia.getFirst().host()).isEqualTo("nvidia.wd5.myworkdayjobs.com");
        assertThat(nvidia.getFirst().site()).isEqualTo("NVIDIAExternalCareerSite");
    }

    @Test
    void recordFailureFlipsToDeadAndDisabledExactlyAtThreshold() {
        long id = onlyId(repository.list("greenhouse", "airbnb", false, 1, 0));
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");

        repository.recordFailure(id, t1, 3);
        AtsCompany afterOne = repository.findById(id).orElseThrow();
        assertThat(afterOne.consecutiveFailures()).isEqualTo(1);
        assertThat(afterOne.status()).isEqualTo("active");
        assertThat(afterOne.enabled()).isTrue();

        repository.recordFailure(id, t1, 3);
        AtsCompany afterTwo = repository.findById(id).orElseThrow();
        assertThat(afterTwo.status()).isEqualTo("active");
        assertThat(afterTwo.enabled()).isTrue();

        repository.recordFailure(id, t1, 3);
        AtsCompany afterThree = repository.findById(id).orElseThrow();
        assertThat(afterThree.consecutiveFailures()).isEqualTo(3);
        assertThat(afterThree.status()).isEqualTo("dead");
        assertThat(afterThree.enabled()).isFalse();
    }

    @Test
    void recordSuccessClearsFailureStreakAndReactivates() {
        long id = onlyId(repository.list("lever", "palantir", false, 1, 0));
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        repository.recordFailure(id, t1, 5);
        repository.recordFailure(id, t1, 5);

        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        repository.recordSuccess(id, t2, 17);

        AtsCompany after = repository.findById(id).orElseThrow();
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(after.status()).isEqualTo("active");
        assertThat(after.lastOkAt()).isEqualTo(t2);
        assertThat(after.lastCheckedAt()).isEqualTo(t2);
        assertThat(after.lastJobCount()).isEqualTo(17);
    }

    @Test
    void findForRunExcludesDeadAndDisabledRows() {
        long deadId = onlyId(repository.list("greenhouse", "stripe", false, 1, 0));
        repository.recordFailure(deadId, Instant.parse("2026-01-01T00:00:00Z"), 1);
        assertThat(repository.findById(deadId).orElseThrow().status()).isEqualTo("dead");

        long disabledId = onlyId(repository.list("greenhouse", "coinbase", false, 1, 0));
        repository.setEnabled(disabledId, false);

        List<AtsCompany> forRun = repository.findForRun(List.of("greenhouse"), 1000);
        assertThat(forRun).extracting(AtsCompany::id).doesNotContain(deadId, disabledId);
        // 26 greenhouse seeds minus the two just excluded.
        assertThat(forRun).hasSize(24);
        assertThat(forRun).allMatch(AtsCompany::enabled);
        assertThat(forRun).noneMatch(c -> "dead".equals(c.status()));
    }

    @Test
    void searchIsCaseInsensitiveSubstringOnCompanyOrSlug() {
        List<AtsCompany> byCompany = repository.list(null, "SCALE", false, 10, 0);
        assertThat(byCompany).extracting(AtsCompany::company).containsExactly("Scale AI");

        List<AtsCompany> bySlug = repository.list(null, "shieldai", false, 10, 0);
        assertThat(bySlug).extracting(AtsCompany::slug).containsExactly("shieldai");
    }

    @Test
    void listIsPaginated() {
        List<AtsCompany> page1 = repository.list(null, null, false, 5, 0);
        List<AtsCompany> page2 = repository.list(null, null, false, 5, 5);
        assertThat(page1).hasSize(5);
        assertThat(page2).hasSize(5);
        assertThat(page1).extracting(AtsCompany::id).doesNotContainAnyElementsOf(
                page2.stream().map(AtsCompany::id).toList());
        assertThat(repository.count(null, null, false)).isEqualTo(42);
    }

    @Test
    void insertOrIgnoreDoesNotClobberAnExistingEnabledRow() {
        long airbnbId = onlyId(repository.list("greenhouse", "airbnb", false, 1, 0));
        AtsCompany before = repository.findById(airbnbId).orElseThrow();
        assertThat(before.enabled()).isTrue();
        assertThat(before.company()).isEqualTo("Airbnb");

        int inserted = repository.insertOrIgnore(
                List.of(new NewCompany("greenhouse", "airbnb", "Some Other Name", null, null)),
                false, "unverified", Instant.parse("2026-02-01T00:00:00Z"));

        assertThat(inserted).isZero();
        AtsCompany after = repository.findById(airbnbId).orElseThrow();
        assertThat(after.enabled()).isTrue();
        assertThat(after.company()).isEqualTo("Airbnb");
        assertThat(after.status()).isEqualTo("active");
    }

    @Test
    void deleteDeadRemovesOnlyDeadRows() {
        long id = onlyId(repository.list("greenhouse", "elastic", false, 1, 0));
        repository.recordFailure(id, Instant.parse("2026-01-01T00:00:00Z"), 1);

        int deleted = repository.deleteDead();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(id)).isEmpty();
        assertThat(repository.count(null, null, false)).isEqualTo(41);
    }

    @Test
    void upsertSiteCachesResolvedWorkdaySite() {
        long id = onlyId(repository.list("workday", "workday", false, 1, 0));
        repository.upsertSite(id, "SomeDiscoveredSite");
        assertThat(repository.findById(id).orElseThrow().site()).isEqualTo("SomeDiscoveredSite");
    }

    @Test
    void insertOneReturnsEmptyWhenSlugAlreadyExists() {
        Optional<Long> result = repository.insertOne("greenhouse", "airbnb", "Airbnb Duplicate",
                null, null, true, "unverified", Instant.now());
        assertThat(result).isEmpty();
    }

    @Test
    void findByAtsAndCompanyMatchesCaseInsensitivelyAndPrefersEnabled() {
        Optional<AtsCompany> found = repository.findByAtsAndCompany("greenhouse", "AIRBNB");
        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("airbnb");
        assertThat(found.get().company()).isEqualTo("Airbnb");

        assertThat(repository.findByAtsAndCompany("greenhouse", "NoSuchCompany")).isEmpty();
        assertThat(repository.findByAtsAndCompany("lever", "Airbnb")).isEmpty();
    }

    @Test
    void findByAtsAndCompanyPrefersEnabledRowOverDisabledDuplicate() {
        long airbnbId = onlyId(repository.list("greenhouse", "airbnb", false, 1, 0));
        long unverifiedId = repository.insertOne("greenhouse", "airbnb-alt", "Airbnb", null, null,
                        false, "unverified", Instant.parse("2026-02-01T00:00:00Z"))
                .orElseThrow();

        Optional<AtsCompany> found = repository.findByAtsAndCompany("greenhouse", "airbnb");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(airbnbId);
        assertThat(found.get().enabled()).isTrue();
        assertThat(unverifiedId).isNotEqualTo(airbnbId);
    }

    private static long onlyId(List<AtsCompany> rows) {
        assertThat(rows).hasSize(1);
        return rows.getFirst().id();
    }
}
