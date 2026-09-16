package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.ProfileAnswer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ProfileAnswerRepository} against a real (temp-file) migrated database. */
class ProfileAnswerRepositoryTest extends AbstractStoreTest {

    @Test
    void recordUnansweredInsertsNewPendingRowOnFirstAsk() {
        ProfileAnswerRepository.Recorded recorded = profileAnswerRepository.recordUnanswered(
                "Do you require sponsorship?", 1L, "Acme", Instant.parse("2026-09-17T00:00:00Z"));

        assertThat(recorded.created()).isTrue();
        assertThat(recorded.row().status()).isEqualTo("pending");
        assertThat(recorded.row().askedCount()).isEqualTo(0);
        assertThat(recorded.row().lastJobId()).isEqualTo(1L);
        assertThat(recorded.row().lastCompany()).isEqualTo("Acme");
    }

    @Test
    void recordUnansweredBumpsExistingPendingRowInsteadOfDuplicating() {
        profileAnswerRepository.recordUnanswered("Do you require sponsorship?", 1L, "Acme",
                Instant.parse("2026-09-17T00:00:00Z"));

        ProfileAnswerRepository.Recorded second = profileAnswerRepository.recordUnanswered(
                "do you require sponsorship?", 2L, "Widgets Inc", Instant.parse("2026-09-18T00:00:00Z"));

        assertThat(second.created()).isFalse();
        assertThat(second.row().askedCount()).isEqualTo(1);
        assertThat(second.row().lastJobId()).isEqualTo(2L);
        assertThat(second.row().lastCompany()).isEqualTo("Widgets Inc");

        assertThat(profileAnswerRepository.list()).hasSize(1);
    }

    @Test
    void recordUnansweredNeverFlipsAnAnsweredRowBackToPending() {
        long id = profileAnswerRepository.insert("Work authorization?", "US Citizen", "answered", null, "",
                Instant.now());

        ProfileAnswerRepository.Recorded recorded = profileAnswerRepository.recordUnanswered(
                "Work authorization?", 5L, "Acme", Instant.now());

        assertThat(recorded.created()).isFalse();
        assertThat(recorded.row().id()).isEqualTo(id);
        assertThat(recorded.row().status()).isEqualTo("answered");
        assertThat(recorded.row().answer()).isEqualTo("US Citizen");
        assertThat(recorded.row().askedCount()).isEqualTo(1);
    }

    @Test
    void listReturnsPendingRowsBeforeAnsweredRowsThenAlphabetically() {
        profileAnswerRepository.insert("Zebra question", "answer", "answered", null, "", Instant.now());
        profileAnswerRepository.insert("Alpha question", "", "pending", null, "", Instant.now());
        profileAnswerRepository.insert("Beta question", "", "pending", null, "", Instant.now());

        List<ProfileAnswer> answers = profileAnswerRepository.list();

        assertThat(answers).extracting(ProfileAnswer::question)
                .containsExactly("Alpha question", "Beta question", "Zebra question");
        assertThat(answers.get(0).status()).isEqualTo("pending");
        assertThat(answers.get(2).status()).isEqualTo("answered");
    }

    @Test
    void setAnswerFlipsStatusByBlankness() {
        long id = profileAnswerRepository.insert("Salary expectation?", "", "pending", null, "", Instant.now());

        profileAnswerRepository.setAnswer(id, "$150,000", Instant.now());
        assertThat(profileAnswerRepository.findByKey("salary expectation").orElseThrow().status())
                .isEqualTo("answered");

        profileAnswerRepository.setAnswer(id, "  ", Instant.now());
        assertThat(profileAnswerRepository.findByKey("salary expectation").orElseThrow().status())
                .isEqualTo("pending");
    }

    @Test
    void deleteRemovesTheRow() {
        long id = profileAnswerRepository.insert("Some question", "answer", "answered", null, "", Instant.now());
        profileAnswerRepository.delete(id);
        assertThat(profileAnswerRepository.list()).isEmpty();
    }
}
