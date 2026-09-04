package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Resume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-SQLite-plus-Flyway tests for {@link ResumeRepository}, following {@link AbstractStoreTest}.
 * {@code ResumeRepository} isn't wired into the base class (it's new in this task), so it's
 * built locally from the inherited {@code client}.
 */
class ResumeRepositoryTest extends AbstractStoreTest {

    private ResumeRepository resumeRepository;

    @BeforeEach
    void setUpResumeRepository() {
        this.resumeRepository = new ResumeRepository(client);
    }

    private Resume sample(String name, boolean isDefault) {
        return new Resume(0, name, name + ".txt", "text/plain", "data/resumes/placeholder",
                "some extracted text", 20, isDefault, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void insertAssignsIdAndFindByIdReturnsFullRow() {
        long id = resumeRepository.insert(sample("Backend - senior", true));

        Resume found = resumeRepository.findById(id).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo("Backend - senior");
        assertThat(found.contentText()).isEqualTo("some extracted text");
        assertThat(found.isDefault()).isTrue();
    }

    @Test
    void listReturnsNewestFirstAndExcludesContentText() {
        long first = resumeRepository.insert(sample("First", false));
        long second = resumeRepository.insert(sample("Second", false));

        List<ResumeRepository.ResumeSummary> summaries = resumeRepository.list();

        assertThat(summaries).extracting(ResumeRepository.ResumeSummary::id)
                .containsExactly(second, first);
    }

    @Test
    void setDefaultLeavesExactlyOneDefaultRow() {
        long first = resumeRepository.insert(sample("First", true));
        long second = resumeRepository.insert(sample("Second", false));
        long third = resumeRepository.insert(sample("Third", false));

        resumeRepository.setDefault(third);

        List<ResumeRepository.ResumeSummary> summaries = resumeRepository.list();
        long defaultCount = summaries.stream().filter(ResumeRepository.ResumeSummary::isDefault).count();
        assertThat(defaultCount).isEqualTo(1);
        assertThat(resumeRepository.findDefault().orElseThrow().id()).isEqualTo(third);

        // Sanity: the previously-default row really was cleared, not just shadowed by a new one.
        assertThat(resumeRepository.findById(first).orElseThrow().isDefault()).isFalse();
        assertThat(resumeRepository.findById(second).orElseThrow().isDefault()).isFalse();
    }

    @Test
    void deleteRemovesTheRow() {
        long id = resumeRepository.insert(sample("Temp", false));

        resumeRepository.delete(id);

        assertThat(resumeRepository.findById(id)).isEmpty();
    }

    @Test
    void findDefaultReturnsEmptyWhenNoneSet() {
        resumeRepository.insert(sample("No default", false));

        assertThat(resumeRepository.findDefault()).isEmpty();
    }
}
