package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.store.AbstractStoreTest;
import com.ubaid.jobdash.store.LcaWageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LcaSalarySourceTest extends AbstractStoreTest {

    private LcaSalarySource source;

    @BeforeEach
    void setUpSource() {
        source = new LcaSalarySource(lcaWageRepository, new SocMapper(), Clock.systemUTC());
    }

    private static LcaWageRow row(String employer, String soc, String state, double p50) {
        return new LcaWageRow(employer, soc, state, p50 - 20000, p50, p50 + 25000, p50 + 60000, 12, "2025-03-15");
    }

    private static SalaryLookup lookup(String company, String title, String location) {
        return new SalaryLookup(company, title, location,
                CompanyKey.of(company), TitleKey.of(title));
    }

    @Test
    void mapsP25ToMinAndP75ToMax() {
        lcaWageRepository.upsertAll(List.of(row("acme robotics", "15-1252", "CA", 180000)));

        SalaryResult r = source.lookup(lookup("Acme Robotics Inc", "Senior Software Engineer",
                "San Francisco, California, United States")).orElseThrow();

        assertThat(r.salaryMin()).isEqualTo(160000.0);
        assertThat(r.salaryMax()).isEqualTo(205000.0);
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.source()).isEqualTo("lca");
        assertThat(r.sampleCount()).isEqualTo(12);
        assertThat(r.dataDate()).isEqualTo(LocalDate.of(2025, 3, 15));
    }

    @Test
    void fallsThroughSocCandidatesAndToNationalAggregate() {
        // Only a national row under a neighbour SOC exists; the mapper's primary for "software
        // engineer" is 15-1252, neighbours include 15-1299.
        lcaWageRepository.upsertAll(List.of(row("acme robotics", "15-1299", "", 150000)));

        SalaryResult r = source.lookup(lookup("Acme Robotics", "Software Engineer",
                "Nowhere, Montana, United States")).orElseThrow();
        assertThat(r.salaryMax()).isEqualTo(175000.0);
    }

    @Test
    void emptyWhenNoWageRowMatches() {
        lcaWageRepository.upsertAll(List.of(row("other corp", "15-1252", "", 150000)));
        assertThat(source.lookup(lookup("Acme Robotics", "Software Engineer", "Austin, Texas"))).isEmpty();
    }

    @Test
    void emptyWhenCompanyKeyBlank() {
        assertThat(source.lookup(lookup("Inc", "Software Engineer", "Austin, Texas"))).isEmpty();
    }

    @Test
    void emptyWhenTitleHasNoSocCandidates() {
        lcaWageRepository.upsertAll(List.of(row("acme robotics", "15-1252", "", 150000)));
        Optional<SalaryResult> r = source.lookup(lookup("Acme Robotics", "Underwater Basket Weaver", "Austin, Texas"));
        assertThat(r).isEmpty();
    }
}
