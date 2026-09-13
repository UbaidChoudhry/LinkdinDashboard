package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.ApplicantProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ApplicantProfileRepository} against a real (temp-file) database. */
class ApplicantProfileRepositoryTest extends AbstractStoreTest {

    @Test
    void findIsEmptyBeforeAnyProfileIsSaved() {
        assertThat(applicantProfileRepository.find()).isEmpty();
    }

    @Test
    void saveThenOverwriteRoundTripsThroughFind() {
        ApplicantProfile first = new ApplicantProfile("Ada Lovelace", "ada@example.com", "555-1000",
                "New York, NY", "https://linkedin.com/in/ada", "https://ada.dev", "US Citizen",
                false, "$150,000", "Ask me anything else", Instant.parse("2026-09-01T00:00:00Z"));
        applicantProfileRepository.save(first);

        Optional<ApplicantProfile> loaded = applicantProfileRepository.find();
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(first);

        ApplicantProfile updated = new ApplicantProfile("Ada Lovelace", "ada2@example.com", "555-2000",
                "Remote", "https://linkedin.com/in/ada", "https://ada.dev", "Needs sponsorship",
                true, "$180,000", "", Instant.parse("2026-09-05T00:00:00Z"));
        applicantProfileRepository.save(updated);

        Optional<ApplicantProfile> reloaded = applicantProfileRepository.find();
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get()).isEqualTo(updated);
        assertThat(reloaded.get().email()).isEqualTo("ada2@example.com");
        assertThat(reloaded.get().requiresSponsorship()).isTrue();
    }
}
