package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.web.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the upload/delete/default-promotion pipeline end to end against a real temporary
 * SQLite file (same spirit as {@code AbstractStoreTest}) and a {@code @TempDir} storage
 * directory - never the real {@code data/}.
 */
class ResumeServiceTest {

    @TempDir
    Path tempDbDir;

    @TempDir
    Path storageDir;

    private ResumeService resumeService;
    private ResumeRepository resumeRepository;
    private AiMatchRepository aiMatchRepository;
    private JdbcClient client;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tempDbDir.resolve("jobdash-test.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.client = JdbcClient.create(dataSource);
        this.resumeRepository = new ResumeRepository(client);
        this.aiMatchRepository = new AiMatchRepository(client);
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        this.resumeService = new ResumeService(resumeRepository, aiMatchRepository,
                new ResumeTextExtractor(), clock, storageDir);
    }

    private MockMultipartFile textFile(String filename, String body) {
        return new MockMultipartFile("file", filename, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private String longEnoughBody(String marker) {
        return marker + " " + "Experienced software engineer with a decade of backend work. ".repeat(6);
    }

    @Test
    void firstUploadBecomesDefault() {
        Resume resume = resumeService.upload(textFile("resume.txt", longEnoughBody("first")), null);

        assertThat(resume.isDefault()).isTrue();
        assertThat(resumeRepository.findDefault().orElseThrow().id()).isEqualTo(resume.id());
    }

    @Test
    void secondUploadDoesNotBecomeDefault() {
        resumeService.upload(textFile("one.txt", longEnoughBody("one")), null);
        Resume second = resumeService.upload(textFile("two.txt", longEnoughBody("two")), null);

        assertThat(second.isDefault()).isFalse();
    }

    @Test
    void deletingDefaultPromotesNewestRemaining() {
        Resume first = resumeService.upload(textFile("one.txt", longEnoughBody("one")), null);
        Resume second = resumeService.upload(textFile("two.txt", longEnoughBody("two")), null);
        assertThat(first.isDefault()).isTrue();

        resumeService.delete(first.id());

        assertThat(resumeRepository.findDefault().orElseThrow().id()).isEqualTo(second.id());
    }

    @Test
    void deletingRemovesFileFromDisk() throws IOException {
        Resume resume = resumeService.upload(textFile("resume.txt", longEnoughBody("body")), null);
        Path storedPath = Path.of(resumeRepository.findById(resume.id()).orElseThrow().storedPath());
        assertThat(Files.exists(storedPath)).isTrue();

        resumeService.delete(resume.id());

        assertThat(Files.exists(storedPath)).isFalse();
        assertThat(resumeRepository.findById(resume.id())).isEmpty();
    }

    @Test
    void deletingAlsoRemovesTheOriginalNameCopyMadeForApplying() throws IOException {
        Resume resume = resumeService.upload(textFile("Choudhry_Resume V19.txt", longEnoughBody("body")), null);
        Resume stored = resumeRepository.findById(resume.id()).orElseThrow();
        Path namedCopy = ResumeUploadFile.forUpload(stored);
        assertThat(namedCopy.getFileName().toString()).isEqualTo("Choudhry_Resume V19.txt");
        assertThat(Files.exists(namedCopy)).isTrue();

        resumeService.delete(resume.id());

        assertThat(Files.exists(namedCopy)).isFalse();
        assertThat(Files.exists(namedCopy.getParent())).isFalse();
    }

    @Test
    void enforcesFiveMegabyteCeiling() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", tooLarge);

        assertThatThrownBy(() -> resumeService.upload(file, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    void nameDefaultsToFilenameWithoutExtension() {
        Resume resume = resumeService.upload(textFile("Backend - senior.txt", longEnoughBody("body")), null);

        assertThat(resume.name()).isEqualTo("Backend - senior");
    }

    @Test
    void listExcludesContentText() {
        resumeService.upload(textFile("resume.txt", longEnoughBody("body")), "Custom name");

        List<ResumeRepository.ResumeSummary> summaries = resumeService.list();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).name()).isEqualTo("Custom name");
    }

    /**
     * `ai_match.resume_id` declares `on delete cascade`, but SQLite ships with foreign-key
     * enforcement OFF, so that clause does nothing and deleting a resume used to leave its
     * cached verdicts behind - a silent leak that no error ever reported. Verified against a
     * real database before the explicit delete was added.
     */
    @Test
    void deletingAResumeAlsoRemovesItsCachedAiVerdicts() {
        var created = resumeService.upload(textFile("cv.txt", longEnoughBody("cv")), "CV");

        client.sql("""
                        insert into ai_match (job_id, resume_id, recommended, reason, model, run_id, scanned_at)
                        values (4242, :resumeId, 1, 'looks good', 'sonnet', 1, '2026-09-01T00:00:00Z')
                        """)
                .param("resumeId", created.id())
                .update();
        assertThat(countMatchesFor(created.id())).as("precondition: the verdict row exists").isEqualTo(1);

        resumeService.delete(created.id());

        assertThat(countMatchesFor(created.id()))
                .as("deleting a resume must remove its cached ai_match rows - SQLite's cascade does not fire")
                .isZero();
    }

    private int countMatchesFor(long resumeId) {
        return client.sql("select count(*) from ai_match where resume_id = :id")
                .param("id", resumeId)
                .query(Integer.class)
                .single();
    }
}
